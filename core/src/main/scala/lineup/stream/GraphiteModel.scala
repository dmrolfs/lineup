package lineup.stream

import java.io.OutputStream
import java.net.{ Socket, InetAddress }
import java.util.concurrent.atomic.AtomicInteger
import akka.stream.stage.{ SyncDirective, Context, PushStage }
import better.files.{ ManagedResource => _, _ }
import resource._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.matching.Regex
import scala.util.{ Try, Failure, Success }

import akka.actor.{ ActorRef, ActorSystem }
import akka.stream.scaladsl._
import akka.stream._
import akka.util.ByteString

import com.typesafe.config._
import com.typesafe.scalalogging.{ StrictLogging, Logger }
import org.slf4j.LoggerFactory
import peds.commons.log.Trace
import peds.commons.collection.BloomFilter

import lineup.analysis.outlier.algorithm.DBSCANAnalyzer
import lineup.analysis.outlier.{ DetectionAlgorithmRouter, OutlierDetection }
import lineup.model.outlier._
import lineup.model.timeseries.TimeSeriesBase.Merging
import lineup.model.timeseries._


/**
 * Created by rolfsd on 10/21/15.
 */
object GraphiteModel extends StrictLogging {

  val OutlierGaugeSuffix = ".outlier"

  override protected val logger: Logger = Logger( LoggerFactory.getLogger("Graphite") )

  val trace = Trace[GraphiteModel.type]

  case class UsageConfiguration(
    sourceHostPort: (InetAddress, Int),
    maxFrameLength: Int,
    protocol: GraphiteSerializationProtocol,
    windowDuration: FiniteDuration,
    graphiteHostPort: Option[(InetAddress, Int)]
  ) {
    def serverBinding( implicit system: ActorSystem ): Source[Tcp.IncomingConnection, Future[Tcp.ServerBinding]] = {
      val (host, port) = sourceHostPort
      Tcp().bind( host.getHostAddress, port )
    }

    def graphiteStream: Option[ManagedResource[OutputStream]] = {
      graphiteHostPort flatMap {
        case (h, p) => { Try { managed( new Socket(h, p) ) flatMap { socket => managed( socket.getOutputStream ) } }.toOption }
      }
    }
  }

  def main( args: Array[String] ): Unit = {
    Settings.makeUsageConfig.parse( args, Settings.zero ) match {
      case None => System exit -1

      case Some( usage ) => {
        val config = ConfigFactory.load
        val uconfig = getConfiguration( usage, config )

        val PlanConfigPath = "lineup.detection-plans"
        val plansFn: () => Try[Seq[OutlierPlan]] = () => Try {
          ConfigFactory.invalidateCaches()
          makePlans( ConfigFactory.load.getConfig(PlanConfigPath) )
        }

        plansFn() foreach { plans =>
          val usageMessage = s"""
            |\nRunning Lineup using the following configuration:
            |\tbinding       : ${uconfig.sourceHostPort._1}:${uconfig.sourceHostPort._2}
            |\tmax frame size: ${uconfig.maxFrameLength}
            |\tprotocol      : ${uconfig.protocol}
            |\twindow        : ${uconfig.windowDuration.toCoarsest}
            |\tplans         : [${plans.zipWithIndex.map{ case (p,i) => f"${i}%2d: ${p}"}.mkString("\n","\n","\n")}]
          """.stripMargin

          logger info usageMessage
        }

        val decider: Supervision.Decider = {
          case ex => {
            logger.error( "Error caught by Supervisor:", ex )
            Supervision.Stop
          }
        }

        implicit val system = ActorSystem( "Graphite" )
        implicit val materializer = ActorMaterializer( ActorMaterializerSettings(system).withSupervisionStrategy(decider) )
        implicit val ec = system.dispatcher

        import java.nio.file.{ StandardWatchEventKinds => Events }
        import better.files.FileWatcher._

        val router = system.actorOf( DetectionAlgorithmRouter.props, "router" )
        val dbscan = system.actorOf( DBSCANAnalyzer.props( router ), "dbscan" )

        val detector = system.actorOf(
          props = OutlierDetection.props(router ){ r =>
            new OutlierDetection(r) with OutlierDetection.ConfigPlanPolicy {
              override def getPlans: () => Try[Seq[OutlierPlan]] = plansFn
              override def refreshInterval: FiniteDuration = 15.minutes
            }
          },
          name = "outlierDetector"
        )

        val configOrigin = config.getConfig( PlanConfigPath ).origin
        logger info s"origin for ${PlanConfigPath}: [${configOrigin}]"

        determineConfigFileComponents( configOrigin ) foreach { filename =>
          // note: attempting to watch a shared file wrt VirtualBox will not work (https://www.virtualbox.org/ticket/9069)
          // so dev testing of watching should be done by running the Java locally
          logger info s"watching for changes in ${filename}"
          val configWatcher = File( filename ).newWatcher( true )
          configWatcher ! on( Events.ENTRY_MODIFY ) {
            case _ => {
              logger info s"config file watcher sending reload command due to change in ${configOrigin.description()}"
              detector ! OutlierDetection.ReloadPlans
            }
          }
        }

        streamGraphiteOutliers( uconfig, detector, PlanConfigPath ) onComplete {
          case Success(_) => system.terminate()
          case Failure( e ) => {
            logger.error( "Failure:", e )
            system.terminate()
          }
        }
      }
    }
  }

  def determineConfigFileComponents(origin: ConfigOrigin ): List[String] = {
    val Path = "@\\s+file:(.*):\\s+\\d+,".r
    Path.findAllMatchIn( origin.toString ).map{ _ group 1 }.toList
  }


  def streamGraphiteOutliers(
    usageConfig: UsageConfiguration,
    detector: ActorRef,
    planConfigPath: String
  )(
    implicit system: ActorSystem,
    materializer: Materializer
  ): Future[Unit] = {
    implicit val dispatcher = system.dispatcher

    usageConfig.serverBinding runForeach { connection =>
      ConfigFactory.invalidateCaches()
      val config = ConfigFactory.load
      val tcpInBufferSize = config.getInt( "lineup.source.buffer" )
      val workflowBufferSize = config.getInt( "lineup.workflow.buffer" )
      val detectTimeout = FiniteDuration( config.getDuration("lineup.workflow.detect.timeout", MILLISECONDS), MILLISECONDS )
      val plans = makePlans( config.getConfig(planConfigPath) )
      log( logger, 'info ){
        s"""
           |\nConnection made using the following configuration:
           |\tTCP-In Buffer Size   : ${tcpInBufferSize}
           |\tWorkflow Buffer Size : ${workflowBufferSize}
           |\tDetect Timeout       : ${detectTimeout.toCoarsest}
           |\tplans                : [${plans.zipWithIndex.map{ case (p,i) => f"${i}%2d: ${p}"}.mkString("\n","\n","\n")}]
        """.stripMargin
      }

      val detection = Flow() { implicit b =>
        import FlowGraph.Implicits._

        log( logger, 'info )( s"received connection remote[${connection.remoteAddress}] -> local[${connection.localAddress}]" )

        val framing = b.add( Monitor.source('framing).watch( usageConfig.protocol.framingFlow(usageConfig.maxFrameLength) ) )
        val timeSeries = b.add( Monitor.source('timeseries).watch(usageConfig.protocol.loadTimeSeriesData) )
        val planned = b.add( Monitor.flow('planned).watch( filterPlanned(plans) ) )

        val batch = Monitor.sink('batch).watch( batchSeries(usageConfig.windowDuration) )

        val buf1 = b.add(Monitor.flow('buffer1).watch(Flow[ByteString].buffer(tcpInBufferSize, OverflowStrategy.backpressure)))

        val buf2 = b.add(
          Monitor.source('groups).watch(
            Flow[TimeSeries]
            .via( Monitor.flow('buffer2).watch( Flow[TimeSeries].buffer(workflowBufferSize, OverflowStrategy.backpressure) ) )
          )
        )

        val detectOutlier = Monitor.flow('detect).watch(
          OutlierDetection.detectOutlier( detector, detectTimeout, Runtime.getRuntime.availableProcessors() * 3 )
        )

        val broadcast = b.add( Broadcast[Outliers](outputPorts = 2, eagerCancel = false) )
        val publish = b.add( Monitor.flow('publish).watch( publishOutliers(usageConfig.graphiteStream) ) )
        val tcpOut = b.add( Monitor.sink('tcpOut).watch( Flow[Outliers] map { _ => ByteString() } ) )
        val train = Monitor.sink('train).watch( TrainOutlierAnalysis.feedOutlierTraining )
        val term = b.add( Sink.ignore )

        Monitor.set( 'framing, 'buffer1, 'timeseries, 'planned, 'batch, 'groups, 'buffer2, 'detect, 'publish, 'tcpOut, 'train )

        framing ~> buf1 ~> timeSeries ~> planned ~> batch ~> buf2 ~> detectOutlier ~> broadcast ~> publish ~> tcpOut
                                                                                      broadcast ~> train ~> term

        ( framing.inlet, tcpOut.outlet )
      }

      connection.handleWith( detection )
    }
  }

  def filterPlanned( plans: Seq[OutlierPlan] )( implicit system: ActorSystem ): Flow[TimeSeries, TimeSeries, Unit] = {
    def logMetric: PushStage[TimeSeries, TimeSeries] = new PushStage[TimeSeries, TimeSeries] {
      val count = new AtomicInteger( 0 )
      var bloom = BloomFilter[Topic]( maxFalsePosProbability = 0.001, 500000 )
      val metricLogger = Logger( LoggerFactory getLogger "Metrics" )

      override def onPush( elem: TimeSeries, ctx: Context[TimeSeries] ): SyncDirective = {
        if ( bloom has_? elem.topic ) ctx push elem
        else {
          bloom += elem.topic
          log( metricLogger, 'debug ){
            s"""[${count.incrementAndGet}] Plan for ${elem.topic}: ${plans.find{ _ appliesTo elem }.getOrElse{"NONE"}}"""
          }
          ctx push elem
        }
      }
    }

    Flow[TimeSeries]
    .transform( () => logMetric )
    .filter { ts => !ts.topic.name.endsWith( OutlierGaugeSuffix ) && plans.exists{ _ appliesTo ts } }
  }

  def publishOutliers(
    graphiteStream: Option[ManagedResource[OutputStream]]
  )(
    implicit system: ActorSystem
  ): Flow[Outliers, Outliers, Unit] = {
    val outlierLogger = Logger( LoggerFactory getLogger "Outliers" )
    implicit val ec = loggerDispatcher( system )

    //todo: after akka stream update: refactor to custom stage/graph to incorporate outlier batching before sending to graphite
    Flow[Outliers]
    .mapAsync( Runtime.getRuntime.availableProcessors ) {
      case o @ SeriesOutliers(_, source, outliers) => {
        import PythonPickleProtocol._
        val marks = outliers map { case DataPoint(ts, _) => DataPoint(ts, 1D) }

        val report = pickle( TimeSeries(topic = source.topic + OutlierGaugeSuffix, points = marks) ).withHeader
        Future {
          outlierLogger info o.toString
          graphiteStream foreach { gs =>
            logger error s"SENDING OUTLIER RESULT to graphite: [${o.toString}]"
            gs foreach { out =>
              out write report.toArray
              out.flush
            }
          }

          o
        }
      }

      case o: Outliers => {
        val df = Future { logger info s"NOT SENDING: ${o.toString}"}
        val olf = log( outlierLogger, 'info ){ o.toString } map { _ => o }
        for {
          _ <- df
          _ <- olf
        } yield { o }
      }
    }
  }

  /**
    * Limit downstream rate to one element every 'interval' by applying back-pressure on upstream.
    *
    * @param interval time interval to send one element downstream
    * @tparam A
    * @return
    */
  def rateLimiter[A]( interval: FiniteDuration ): Flow[A, A, Unit] = {
    case object Tick

    val flow = Flow() { implicit builder =>
      import FlowGraph.Implicits._

      val rateLimiter = Source.apply( 0.second, interval, Tick )
      val zip = builder add Zip[A, Tick.type]()
      rateLimiter ~> zip.in1

      ( zip.in0, zip.out.map(_._1).outlet )
    }

    // We need to limit input buffer to 1 to guarantee the rate limiting feature
    flow.withAttributes( Attributes.inputBuffer(initial = 1, max = 64) )
  }

  def batchSeries(
    windowSize: FiniteDuration = 1.minute,
    parallelism: Int = 4
  )(
    implicit system: ActorSystem,
    tsMerging: Merging[TimeSeries],
    materializer: Materializer
  ): Flow[TimeSeries, TimeSeries, Unit] = {
    val numTopics = 1

    val n = if ( numTopics * windowSize.toMicros.toInt < 0 ) { numTopics * windowSize.toMicros.toInt } else { Int.MaxValue }
    logger info s"n = [${n}] for windowSize=[${windowSize.toCoarsest}]"
    Flow[TimeSeries]
    .groupedWithin( n, d = windowSize ) // max elems = 1 per micro; duration = windowSize
    .map {
      _.groupBy{ _.topic }
      .map { case (topic, tss) =>
        tss.tail.foldLeft( tss.head ){ (acc, e) => tsMerging.merge( acc, e ) valueOr { exs => throw exs.head } }
      }
    }
    .mapConcat { identity }
  }


  val demoReduce = new ReduceOutliers {
    override def apply(
      results: SeriesOutlierResults,
      source: TimeSeriesBase
    )(
      implicit ec: ExecutionContext
    ): Future[Outliers] = {
      Future {
        results.headOption map { _._2 } getOrElse { NoOutliers( algorithms = Set(DBSCANAnalyzer.Algorithm ), source = source ) }
      }
    }
  }


  def loggerDispatcher( system: ActorSystem ): ExecutionContext = system.dispatchers lookup "logger-dispatcher"

  def log( logr: Logger, level: Symbol )( msg: => String )( implicit system: ActorSystem ): Future[Unit] = {
    Future {
      level match {
        case 'debug => logr debug msg
        case 'info => logr info msg
        case 'warn => logr warn msg
        case 'error => logr error msg
        case _ => logr error msg
      }
    }( loggerDispatcher(system) )
  }


  def getConfiguration( usage: Settings, config: Config ): UsageConfiguration = {
    val sourceHost = usage.sourceHost getOrElse {
      if ( config hasPath Settings.SOURCE_HOST ) InetAddress getByName config.getString( Settings.SOURCE_HOST )
      else InetAddress.getLocalHost
    }

    val sourcePort = usage.sourcePort getOrElse { config getInt Settings.SOURCE_PORT }

    val maxFrameLength = {
      if ( config hasPath Settings.SOURCE_MAX_FRAME_LENGTH) config getInt Settings.SOURCE_MAX_FRAME_LENGTH
      else 4 + scala.math.pow( 2, 20 ).toInt // from graphite documentation
    }

    val protocol = {
      if ( config hasPath Settings.SOURCE_PROTOCOL ) {
        config.getString(Settings.SOURCE_PROTOCOL).toLowerCase match {
          case "messagepack" | "message-pack" => MessagePackProtocol
          case "pickle" => PythonPickleProtocol
          case _ => PythonPickleProtocol
        }
      } else {
        PythonPickleProtocol
      }
    }

    val windowSize = usage.windowSize getOrElse {
      if ( config hasPath Settings.SOURCE_WINDOW_SIZE ) {
        FiniteDuration( config.getDuration( Settings.SOURCE_WINDOW_SIZE ).toNanos, NANOSECONDS )
      } else {
        1.minute
      }
    }

    val graphiteHost = {
      if ( config hasPath Settings.GRAPHITE_HOST ) Some( InetAddress getByName config.getString(Settings.GRAPHITE_HOST) )
      else None
    }

    val graphitePort = if ( config hasPath Settings.GRAPHITE_PORT ) Some(config getInt Settings.GRAPHITE_PORT) else None

    UsageConfiguration(
      sourceHostPort = (sourceHost, sourcePort),
      maxFrameLength,
      protocol,
      windowSize,
      graphiteHostPort = {
        for {
          h <- graphiteHost
          p <- graphitePort
        } yield (h,p)
      }
    )
  }

  private def makePlans( planSpecifications: Config ): Seq[OutlierPlan] = {
    import scala.collection.JavaConversions._

    val result = planSpecifications.root.collect{ case (n, s: ConfigObject) => (n, s.toConfig) }.toSeq.map {
      case (name, spec) => {
        val IS_DEFAULT = "is-default"
        val TOPICS = "topics"
        val REGEX = "regex"

        val ( timeout, algorithms ) = pullCommonPlanFacets( spec )

        if ( spec.hasPath( IS_DEFAULT ) && spec.getBoolean( IS_DEFAULT ) ) {
          Some(
            OutlierPlan.default(
              name = name,
              timeout = timeout,
              isQuorum = IsQuorum.AtLeastQuorumSpecification( totalIssued = algorithms.size, triggerPoint = 1 ),
              reduce = demoReduce,
              algorithms = algorithms,
              specification = spec
            )
          )
        } else if ( spec hasPath TOPICS ) {
          import scala.collection.JavaConverters._
          logger info s"topic[$name] plan origin: ${spec.origin} line:${spec.origin.lineNumber}"

          Some(
            OutlierPlan.forTopics(
              name = name,
              timeout = timeout,
              isQuorum = IsQuorum.AtLeastQuorumSpecification( totalIssued = algorithms.size, triggerPoint = 1 ),
              reduce = demoReduce,
              algorithms = algorithms,
              specification = spec,
              extractTopic = OutlierDetection.extractOutlierDetectionTopic,
              topics = spec.getStringList(TOPICS).asScala.map{ Topic(_) }.toSet
            )
          )
        } else if ( spec hasPath REGEX ) {
          Some(
            OutlierPlan.forRegex(
              name = name,
              timeout = timeout,
              isQuorum = IsQuorum.AtLeastQuorumSpecification( totalIssued = algorithms.size, triggerPoint = 1 ),
              reduce = demoReduce,
              algorithms = algorithms,
              specification = spec,
              extractTopic = OutlierDetection.extractOutlierDetectionTopic,
              regex = new Regex( spec.getString(REGEX) )
            )
          )
        } else {
          None
        }
      }
    }

    result.flatten.sorted
  }


  private def pullCommonPlanFacets( spec: Config ): (FiniteDuration, Set[Symbol]) = {
    import scala.collection.JavaConversions._

    (
      FiniteDuration( spec.getDuration("timeout").toNanos, NANOSECONDS ),
      spec.getStringList("algorithms").toSet.map{ a: String => Symbol(a) }
    )
  }


  case class Settings(
    sourceHost: Option[InetAddress] = None,
    sourcePort: Option[Int] = None,
    windowSize: Option[FiniteDuration] = None,
    plans: Seq[OutlierPlan] = Seq.empty[OutlierPlan]
  )

  object Settings {
    val SOURCE_HOST = "lineup.source.host"
    val SOURCE_PORT = "lineup.source.port"
    val SOURCE_MAX_FRAME_LENGTH = "lineup.source.max-frame-length"
    val SOURCE_PROTOCOL = "lineup.source.protocol"
    val SOURCE_WINDOW_SIZE = "lineup.source.window-size"
    val GRAPHITE_HOST = "lineup.graphite.host"
    val GRAPHITE_PORT = "lineup.graphite.port"

    def zero: Settings = Settings( )

    def makeUsageConfig = new scopt.OptionParser[Settings]( "lineup" ) {
      //todo remove once release is current with corresponding dev
      implicit val inetAddressRead: scopt.Read[InetAddress] = scopt.Read.reads { InetAddress.getByName(_) }

      head( "lineup", "0.1.a" )

      opt[InetAddress]( 'h', "host" ) action { (e, c) =>
        c.copy( sourceHost = Some(e) )
      } text( "connection address to source" )

      opt[Int]( 'p', "port" ) action { (e, c) =>
        c.copy( sourcePort = Some(e) )
      } text( "connection port of source server")

      opt[Long]( 'w', "window" ) action { (e, c) =>
        c.copy( windowSize = Some(FiniteDuration(e, SECONDS)) )
      } text( "batch window size (in seconds) for collecting time series data. Default = 60s." )

      help( "help" )

      note(
        """
          |DBSCAN eps: The value for ε can then be chosen by using a k-distance graph, plotting the distance to the k = minPts
          |nearest neighbor. Good values of ε are where this plot shows a strong bend: if ε is chosen much too small, a large
          |part of the data will not be clustered; whereas for a too high value of ε, clusters will merge and the majority of
          |objects will be in the same cluster. In general, small values of ε are preferable, and as a rule of thumb only a small
          |fraction of points should be within this distance of each other.
          |
          |DBSCAN density: As a rule of thumb, a minimum minPts can be derived from the number of dimensions D in the data set,
          |as minPts ≥ D + 1. The low value of minPts = 1 does not make sense, as then every point on its own will already be a
          |cluster. With minPts ≤ 2, the result will be the same as of hierarchical clustering with the single link metric, with
          |the dendrogram cut at height ε. Therefore, minPts must be chosen at least 3. However, larger values are usually better
          |for data sets with noise and will yield more significant clusters. The larger the data set, the larger the value of
          |minPts should be chosen.
        """.stripMargin
      )
    }
  }
}

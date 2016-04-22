package spotlight.stream

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import akka.stream.FanOutShape.{Init, Name}
import akka.actor._
import akka.stream.scaladsl._
import akka.stream._
import akka.stream.stage.{Context, PushStage, SyncDirective}
import com.typesafe.scalalogging.{Logger, StrictLogging}
import org.slf4j.LoggerFactory
import peds.akka.metrics.Instrumented
import peds.akka.stream.StreamMonitor
import peds.commons.collection.BloomFilter
import spotlight.analysis.outlier.{OutlierDetection, OutlierPlanDetectionRouter}
import spotlight.analysis.outlier.OutlierDetection.DetectionResult
import spotlight.model.outlier._
import spotlight.model.timeseries.TimeSeriesBase.Merging
import spotlight.model.timeseries._
import spotlight.stream.OutlierDetectionBootstrap._


/**
 * Created by rolfsd on 10/21/15.
 */
object OutlierScoringModel extends Instrumented with StrictLogging {
  val OutlierMetricPrefix = "spotlight.outlier."

  object ScoringShape {
    def apply[In, Out, Off]( init: Init[In] ): ScoringShape[In, Out, Off] = new ScoringShape( init )
  }

  class ScoringShape[In, Out, Off]( _init: Init[In] = Name("ScoringStage") ) extends FanOutShape2[In, Out, Off]( _init ) {
    protected override def construct( init: Init[In] ): FanOutShape[In] = new ScoringShape( init )
  }

  object WatchPoints {
    val ScoringPlanned = Symbol("scoring.planned")
    val ScoringUnrecognized = Symbol("scoring.unrecognized")
    val ScoringBatch = Symbol("scoring.batch")
    val ScoringAnalysisBuffer = Symbol("scoring.analysisBuffer")
    val ScoringGrouping = Symbol("scoring.grouping")
    val ScoringDetect = Symbol("scoring.detect")
  }

  def scoringGraph(
    planRouterRef: ActorRef,
    config: Configuration
  )(
    implicit system: ActorSystem,
    materializer: Materializer
  ): Graph[ScoringShape[TimeSeries, Outliers, TimeSeries], Unit] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._
    import StreamMonitor._

    GraphDSL.create() { implicit b =>
      val logMetrics = b.add(
        Flow[TimeSeries].transform( () => logMetric( Logger(LoggerFactory getLogger "Metrics"), config.plans ) )
      )
      val blockPriors = b.add( Flow[TimeSeries].filter{ ts => !isOutlierReport(ts) } )
      val broadcast = b.add( Broadcast[TimeSeries](outputPorts = 2, eagerCancel = false) )

      val watchPlanned = Flow[TimeSeries].map{ identity }.watchConsumed( WatchPoints.ScoringPlanned )
      val passPlanned = b.add( Flow[TimeSeries].filter{ isPlanned( _, config.plans ) }.via( watchPlanned ) )

      val watchUnrecognized = Flow[TimeSeries].map{ identity }.watchConsumed( WatchPoints.ScoringUnrecognized )
      val passUnrecognized = b.add(
        Flow[TimeSeries].filter{ !isPlanned( _, config.plans ) }.via( watchUnrecognized )
      )

      val maxInDetectionCpuFactor = {
        val path = "spotlight.workflow.detect.max-in-flight-cpu-factor"
        if ( config hasPath path ) config.getDouble( path ) else 1.0
      }

      val plansDetectOutliers = b.add(
        OutlierPlanDetectionRouter.elasticPlanDetectionRouterFlow(
          planDetectorRouterRef = planRouterRef,
          maxInDetectionCpuFactor = maxInDetectionCpuFactor,
          plans = () => { config.plans.toSet }
        )
        .watchFlow( WatchPoints.ScoringDetect )
      )

      logMetrics ~> blockPriors ~> broadcast ~> passPlanned ~> plansDetectOutliers
                                   broadcast ~> passUnrecognized

      ScoringShape(
        FanOutShape.Ports(
          inlet = logMetrics.in,
          outlets = scala.collection.immutable.Seq( plansDetectOutliers.out, passUnrecognized.out )
        )
      )
    }
  }


  def logMetric(
    destination: Logger,
    plans: Seq[OutlierPlan]
  ): PushStage[TimeSeries, TimeSeries] = new PushStage[TimeSeries, TimeSeries] {
    val count = new AtomicInteger( 0 )
    var bloom = BloomFilter[Topic]( maxFalsePosProbability = 0.001, 500000 )

    override def onPush( elem: TimeSeries, ctx: Context[TimeSeries] ): SyncDirective = {
      if ( bloom has_? elem.topic ) ctx push elem
      else {
        bloom += elem.topic
        if ( !elem.topic.name.startsWith(OutlierMetricPrefix) ) {
          destination.debug(
            s"""[${count.incrementAndGet}] Plan for ${elem.topic}: ${plans.find{ _ appliesTo elem }.getOrElse{"NONE"}}"""
          )
        }

        ctx push elem
      }
    }
  }

  def filterPlanned( plans: Seq[OutlierPlan] )( implicit system: ActorSystem ): Flow[TimeSeries, TimeSeries, Unit] = {
    Flow[TimeSeries]
    .filter { ts => !isOutlierReport(ts) && isPlanned( ts, plans ) }
  }

  def filterUnrecognized( plans: Seq[OutlierPlan] )( implicit system: ActorSystem ): Flow[TimeSeries, TimeSeries, Unit] = {
    Flow[TimeSeries].filter{ ts => !isOutlierReport(ts) }
  }
  def isOutlierReport( ts: TimeSeriesBase ): Boolean = ts.topic.name.startsWith( OutlierMetricPrefix )
  def isPlanned( ts: TimeSeriesBase, plans: Seq[OutlierPlan] ): Boolean = plans.exists{ _ appliesTo ts }


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
}

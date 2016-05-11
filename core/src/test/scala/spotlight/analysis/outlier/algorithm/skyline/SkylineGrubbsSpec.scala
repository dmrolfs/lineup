package spotlight.analysis.outlier.algorithm.skyline

import scala.collection.immutable
import scala.concurrent.duration._
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.apache.commons.math3.distribution.TDistribution
import org.apache.commons.math3.random.{RandomDataGenerator, RandomGenerator}
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.mockito.Mockito._
import org.joda.{time => joda}
import spotlight.analysis.outlier.{DetectOutliersInSeries, DetectUsing, DetectionAlgorithmRouter, HistoricalStatistics, OutlierDetectionMessage}
import spotlight.model.outlier.{NoOutliers, OutlierPlan, Outliers, SeriesOutliers}
import spotlight.model.timeseries.{ControlBoundary, DataPoint, TimeSeries}

import scala.annotation.tailrec



/**
  * Created by rolfsd on 3/22/16.
  */
class SkylineGrubbsSpec extends SkylineBaseSpec {
  class Fixture extends SkylineFixture {
    val algoS = GrubbsAnalyzer.Algorithm
    val algProps = ConfigFactory.parseString( s"""${algoS.name}.tolerance: 3""" )

    val plan = mock[OutlierPlan]
    when( plan.name ).thenReturn( "mock-plan" )
    when( plan.appliesTo ).thenReturn( SkylineFixture.appliesToAll )
    when( plan.algorithms ).thenReturn( Set(algoS) )

    def fillDataFromHistory(
      original: Seq[DataPoint],
      history: HistoricalStatistics,
      targetSize: Int = HistoricalStatistics.LastN
    ): Seq[DataPoint] = {
      val minPoints = HistoricalStatistics.LastN
      if ( minPoints < original.size ) original
      else {
        val inHistory = history.lastPoints.size
        val needed = minPoints + 1 - original.size
        val past = {
          history.lastPoints
          .drop( inHistory - needed )
          .map { pt => DataPoint( timestamp = new joda.DateTime(pt(0).toLong), value = pt(1) ) }
        }
        past ++ original
      }
    }

    def tailAverageData( data: Seq[DataPoint], last: Seq[DataPoint] = Seq.empty[DataPoint] ): Seq[DataPoint] = {
      val TailLength = 3
      val lastPoints = last.drop( last.size - TailLength + 1 ) map { _.value }
      data.map { _.timestamp }
      .zipWithIndex
      .map { case (ts, i ) =>
        val pointsToAverage = {
          if ( i < TailLength ) {
            val all = lastPoints ++ data.take( i + 1 ).map{ _.value }
            all.drop( all.size - TailLength )
          } else {
            data.drop( i - TailLength + 1 ).take( TailLength ).map{ _.value }
          }
        }

        ( ts, pointsToAverage )
      }
      .map { case (ts, pts) => DataPoint( timestamp = ts, value = pts.sum / pts.size ) }
    }

    def calculateControlBoundaries(
      points: Seq[DataPoint],
      tolerance: Double,
      lastPoints: Seq[DataPoint] = Seq.empty[DataPoint]
    ): Seq[ControlBoundary] = {
      val N = points.size
      val stats = new DescriptiveStatistics( points.map{ _.value }.toArray )
      val mean = stats.getMean
      val stddev = stats.getStandardDeviation

      val tdist = new TDistribution( math.max( N - 2, 1 ) )
      val threshold = tdist.inverseCumulativeProbability( 0.05 / (2.0 * N) )
      val thresholdSquared = math.pow( threshold, 2 )
      val grubbs = ((points.size - 1) / math.sqrt(N)) * math.sqrt( thresholdSquared / (N - 2 + thresholdSquared) )

      points map { case DataPoint(ts, _) => ControlBoundary.fromExpectedAndDistance( ts, mean, tolerance * grubbs * stddev ) }
    }
  }

  override def makeAkkaFixture(): Fixture = new Fixture


  "GrubbsAnalyzer" should {
    "find outliers via Grubbs Test" in { f: Fixture =>
      import f._
      // helpful online grubbs calculator: http://graphpad.com/quickcalcs/Grubbs1.cfm

      val full: Seq[DataPoint] = makeDataPoints(
        values = IndexedSeq.fill( 10 )( 1.0 ).to[scala.collection.immutable.IndexedSeq],
        timeWiggle = (0.98, 1.02),
        valueWiggle = (0.98, 1.02)
      )

      val series = spike( full )()

      val algoS = GrubbsAnalyzer.Algorithm
      val algProps = ConfigFactory.parseString( s"""${algoS.name}.tolerance: 1""" )

      val analyzer = TestActorRef[GrubbsAnalyzer]( GrubbsAnalyzer.props(router.ref) )
      analyzer.receive( DetectionAlgorithmRouter.AlgorithmRegistered( algoS ) )
      val history1 = historyWith( None, series )
      analyzer.receive( DetectUsing( algoS, aggregator.ref, DetectOutliersInSeries(series, plan), history1, algProps ) )
      aggregator.expectMsgPF( 2.seconds.dilated, "grubbs" ) {
        case m @ SeriesOutliers(alg, source, plan, outliers, control) => {
          alg mustBe Set( algoS )
          source mustBe series
          m.hasAnomalies mustBe true
          outliers.size mustBe 1
          outliers mustBe Seq( series.points.last )
        }
      }


      val full2: Seq[DataPoint] = makeDataPoints(
        values = IndexedSeq.fill( 10 )( 1.0 ).to[scala.collection.immutable.IndexedSeq],
        timeWiggle = (0.98, 1.02),
        valueWiggle = (0.98, 1.02)
      )

      val series2 = spike( full )( 0 )
      val history2 = historyWith( Option(history1.recordLastDataPoints(series.points)), series2 )

      analyzer.receive( DetectUsing(algoS, aggregator.ref, DetectOutliersInSeries(series2, plan), history2, algProps ) )
      aggregator.expectMsgPF( 2.seconds.dilated, "grubbs again" ) {
        case m @ NoOutliers(alg, source, plan, control) => {
          alg mustBe Set( algoS )
          source mustBe series2
          m.hasAnomalies mustBe false
        }
      }
    }

    "detect outlier through series of micro events" in { f: Fixture =>
      import f._

      def detectUsing( series: TimeSeries, history: HistoricalStatistics ): DetectUsing = {
        DetectUsing(
          algorithm = algoS,
          aggregator = aggregator.ref,
          payload = OutlierDetectionMessage( series, plan ).toOption.get,
          history = history,
          properties = algProps
        )
      }

      val analyzer = TestActorRef[GrubbsAnalyzer]( GrubbsAnalyzer.props( router.ref ) )
      analyzer receive DetectionAlgorithmRouter.AlgorithmRegistered( algoS )

      val topic = "test.topic"
      val start = joda.DateTime.now
      val rnd = new RandomDataGenerator

      @tailrec def loop( i: Int, left: Int, previous: Option[(TimeSeries, HistoricalStatistics)] = None ): Unit = {
        log.debug( ">>>>>>>>>  TEST-LOOP( i:[{}] left:[{}]", i, left )
        val dt = start plusSeconds (10 * i)
        val v = if ( left == 0 ) 1000.0 else rnd.nextUniform( 0.99, 1.01, true )
        val s = TimeSeries( topic, Seq( DataPoint(dt, v) ) )
        val h = {
          previous
          .map { case (ps, ph) => s.points.foldLeft( ph recordLastDataPoints ps.points ) { (acc, p) => acc :+ p } }
          .getOrElse { HistoricalStatistics.fromActivePoints( DataPoint.toDoublePoints(s.points), false ) }
        }
        analyzer receive detectUsing( s, h )

        val expected: PartialFunction[Any, Unit] = {
          if ( left == 0 ) {
            case m: SeriesOutliers => {
              m.algorithms mustBe Set( algoS )
              m.source mustBe s
              m.hasAnomalies mustBe true
              m.outliers mustBe s.points
            }
          } else {
            case m: NoOutliers => {
              m.algorithms mustBe Set( algoS )
              m.source mustBe s
              m.hasAnomalies mustBe false
            }
          }
        }

        aggregator.expectMsgPF( 5.seconds.dilated, s"point-$i" )( expected )

        if ( left == 0 ) () else loop( i + 1, left - 1, Some( (s, h) ) )
      }

      loop( 0, 125 )
    }

    "provide full control boundaries" taggedAs (WIP) in { f: Fixture =>
      import f._
      val analyzer = TestActorRef[GrubbsAnalyzer]( GrubbsAnalyzer.props( router.ref ) )
      val now = joda.DateTime.now
      val full = makeDataPoints(
        values = immutable.IndexedSeq.fill( 3 )( 1.0 ),
        start = now,
        timeWiggle = (0.97, 1.03),
        valueWiggle = (0.75, 1.25)
      )

      val series = spike( full, 100D )()
      trace( s"test series = $series" )
      val algoS = GrubbsAnalyzer.Algorithm
      val algProps = ConfigFactory.parseString( s"""${algoS.name}.tolerance: 1""" )

      analyzer.receive( DetectionAlgorithmRouter.AlgorithmRegistered( algoS ) )
      val history1 = historyWith( None, series )
      val tailAverages1 = tailAverage(
        series.points,
        history1.lastPoints.map{ p => DataPoint( new joda.DateTime(p(0).toLong), p(1) ) }
      )
      analyzer.receive( DetectUsing( algoS, aggregator.ref, DetectOutliersInSeries(series, plan), history1, algProps ) )
      aggregator.expectMsgPF( 2.seconds.dilated, "sma control" ) {
        case m @ SeriesOutliers(alg, source, plan, outliers, actual) => {
          actual.keySet mustBe Set( algoS )
          val expected = calculateControlBoundaries( points = tailAverages1, tolerance = 1 )
          actual( algoS ).zip( expected ).zipWithIndex foreach  { case ((a, e), i) => (i, a) mustBe (i, e) }
        }
      }

      val full2 = makeDataPoints(
        values = immutable.IndexedSeq.fill( 3 )( 1.0 ),
        start = now.plus( 3000 ),
        timeWiggle = (0.97, 1.03),
        valueWiggle = (0.0, 10)
      )
      val series2 = spike( full2, 100 )( 0 )
      val history2 = historyWith( Option(history1.recordLastDataPoints(series.points)), series2 )
      val tailAverages2 = tailAverage(
        data = fillDataFromHistory(series2.points, history2),
        lastPoints = history2.lastPoints.map{ p => DataPoint( new joda.DateTime(p(0).toLong), p(1) ) }
      )
      analyzer.receive( DetectUsing( algoS, aggregator.ref, DetectOutliersInSeries(series2, plan), history2, algProps ) )
      aggregator.expectMsgPF( 2.seconds.dilated, "sma control again" ) {
        case m: Outliers => {
          val actual = m.algorithmControlBoundaries
          actual.keySet mustBe Set( algoS )
          val expected = calculateControlBoundaries( points = tailAverages2, tolerance = 1.0, lastPoints = tailAverages1 )
          actual( algoS ).zip( expected ).zipWithIndex foreach { case ((a, e), i) => (i, a) mustBe (i,e) }
        }
//        case m @ SeriesOutliers(alg, source, plan, outliers, actual) => {
//          actual.keySet mustBe Set( algoS )
//
//          val expected = calculateControlBoundaries(
//             points = tailAverage(series2.points, series.points),
//             tolerance = 1,
//             lastPoints = tailAverage(series.points)
//           )
//          actual( algoS ).zip( expected ).zipWithIndex foreach { case ((a, e), i) =>
//            (i, a) mustBe (i,e)
//          }

//          control( algoS ).zip( expectedControls ) foreach { case (a, e) =>
//            a.timestamp mustBe e.timestamp
//            a.floor.get mustBe (e.floor.get +- 0.005)
//            a.expected.get mustBe (e.expected.get +- 0.005)
//            a.ceiling.get mustBe (e.ceiling.get +- 0.005)
//          }
//
//        }
      }
    }
  }
}

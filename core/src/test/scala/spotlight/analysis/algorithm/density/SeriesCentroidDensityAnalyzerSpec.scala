package spotlight.analysis.algorithm.density

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit._
import com.github.nscala_time.time.OrderingImplicits._
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.commons.math3.random.RandomDataGenerator
import org.joda.{ time ⇒ joda }
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import omnibus.akka.envelope.WorkId
import omnibus.commons.identifier.{ ShortUUID, TaggedID }
import shapeless._
import spotlight.analysis._
import spotlight.analysis.algorithm.AlgorithmActor
import spotlight.model.outlier._
import spotlight.model.timeseries._
import spotlight.testkit.ParallelAkkaSpec

/** Created by damonrolfs on 9/18/14.
  */
class SeriesCentroidDensityAnalyzerSpec extends ParallelAkkaSpec with MockitoSugar {
  import SeriesCentroidDensityAnalyzerSpec.{ points, pointsA, pointsB }

  override def createAkkaFixture( test: OneArgTest, config: Config, system: ActorSystem, slug: String ): Fixture = {
    new Fixture( config, system, slug )
  }

  class Fixture( _config: Config, _system: ActorSystem, _slug: String ) extends AkkaFixture( _config, _system, _slug ) {
    val algoS = SeriesCentroidDensityAnalyzer.Algorithm
    //    val algoC = CohortDensityAnalyzer.Algorithm
    val plan = mock[AnalysisPlan]
    when( plan.id ).thenReturn( TaggedID( 'plan, ShortUUID() ) )
    val router = TestProbe()
    val subscriber = TestProbe()
    val aggregator = TestProbe()
    val bus = mock[EventStream]
    val randomGenerator = new RandomDataGenerator
    def tweakSeries( s: TimeSeries, factorRange: ( Double, Double ) ): TimeSeries = {
      val factor = randomGenerator.nextUniform( factorRange._1, factorRange._2, true )
      val valueLens = lens[DataPoint] >> 'value
      val tweaked = s.points map { dp ⇒ valueLens.set( dp )( dp.value * factor ) }
      TimeSeries.pointsLens.set( s )( tweaked )
    }
  }

  "SeriesCentroidDensityAnalyzer" should {
    "register with router upon create" in { f: Fixture ⇒
      import f._
      val analyzer = TestActorRef[SeriesCentroidDensityAnalyzer]( SeriesCentroidDensityAnalyzer.props( router.ref ) )
      router.expectMsgPF( 1.second.dilated, "register" ) {
        case DetectionAlgorithmRouter.RegisterAlgorithmReference( algorithm, _ ) ⇒ algorithm must equal( algoS )
      }
    }

    "raise error if used before registration" in { f: Fixture ⇒
      import f._
      val analyzer = TestActorRef[SeriesCentroidDensityAnalyzer]( SeriesCentroidDensityAnalyzer.props( router.ref ) )

      an[AlgorithmActor.AlgorithmUsedBeforeRegistrationError] must be thrownBy
        analyzer.receive(
          DetectUsing(
            plan.id,
            algoS,
            DetectOutliersInSeries( TimeSeries( "series", points ), plan, Option( subscriber.ref ), Set.empty[WorkId] ),
            HistoricalStatistics( 2, false ),
            ConfigFactory.parseString(
              s"""
               |${algoS}.seedEps: 5.0
               |${algoS}.minDensityConnectedPoints: 3
             """.stripMargin
            )
          )
        )
    }

    "detect outlying points in series centroids without shape" in { f: Fixture ⇒
      import f._
      val analyzer = TestActorRef[SeriesCentroidDensityAnalyzer]( SeriesCentroidDensityAnalyzer.props( router.ref ) )
      val series = TimeSeries( "series", points )
      val expectedValues = Seq( 18.8, 25.2, 31.5, 22.0, 24.1, 39.2 )
      val expected = points filter { expectedValues contains _.value } sortBy { _.timestamp }

      val algProps = ConfigFactory.parseString(
        s"""
           |${algoS}.tolerance: 0.01
           |${algoS}.seedEps: 5
           |${algoS}.minDensityConnectedPoints: 3
           |${algoS}.distance: Euclidean
        """.stripMargin
      )

      analyzer.receive( DetectionAlgorithmRouter.AlgorithmRegistered( algoS ) )
      implicit val sender = aggregator.ref
      analyzer ! DetectUsing(
        plan.id,
        algoS,
        DetectOutliersInSeries( series, plan, Option( subscriber.ref ), Set.empty[WorkId] ),
        HistoricalStatistics( 2, false ),
        algProps
      )

      aggregator.expectMsgPF( 2.seconds.dilated, "detect" ) {
        case m @ SeriesOutliers( alg, source, plan, outliers, control ) ⇒ {
          alg mustBe Set( algoS )
          source mustBe series
          m.hasAnomalies mustBe true
          outliers.size mustBe 6
          outliers mustBe expected
        }
      }
    }

    "detect outlying points in series centroids with shape" taggedAs ( WIP ) in { f: Fixture ⇒
      import f._
      val analyzer = TestActorRef[SeriesCentroidDensityAnalyzer]( SeriesCentroidDensityAnalyzer.props( router.ref ) )
      val series = TimeSeries( "series", points )
      val expectedValues = Seq( 18.8, 25.2, 31.5, 22.0, 24.1, 39.2 )
      val expected = points filter { expectedValues contains _.value } sortBy { _.timestamp }

      val algProps = ConfigFactory.parseString(
        s"""
           |${algoS}.tolerance: -1.063505513 // eps:5.0
           |${algoS}.seedEps: 0.0
           |${algoS}.minDensityConnectedPoints: 3
           |${algoS}.distance: Euclidean
        """.stripMargin
      )

      val history = HistoricalStatistics.fromActivePoints( points, false )
      trace( s"shape = $history" )
      analyzer.receive( DetectionAlgorithmRouter.AlgorithmRegistered( algoS ) )
      implicit val sender = aggregator.ref
      analyzer ! DetectUsing(
        plan.id,
        algoS,
        DetectOutliersInSeries( series, plan, Option( subscriber.ref ), Set.empty[WorkId] ),
        history,
        algProps
      )

      aggregator.expectMsgPF( 2.seconds.dilated, "detect" ) {
        case m @ SeriesOutliers( alg, source, plan, outliers, control ) ⇒ {
          alg mustBe Set( algoS )
          source mustBe series
          m.hasAnomalies mustBe true
          outliers.size mustBe 6
          outliers mustBe expected
        }
      }
    }

    "detect no outliers points in series centroids" in { f: Fixture ⇒
      import f._
      val analyzer = TestActorRef[SeriesCentroidDensityAnalyzer]( SeriesCentroidDensityAnalyzer.props( router.ref ) )

      val myPoints = Seq(
        DataPoint( new joda.DateTime( 448 ), 8.46 ),
        DataPoint( new joda.DateTime( 449 ), 8.9 ),
        DataPoint( new joda.DateTime( 450 ), 8.58 ),
        DataPoint( new joda.DateTime( 451 ), 8.36 ),
        DataPoint( new joda.DateTime( 452 ), 8.58 ),
        DataPoint( new joda.DateTime( 453 ), 7.5 ),
        DataPoint( new joda.DateTime( 454 ), 7.1 ),
        DataPoint( new joda.DateTime( 455 ), 7.3 ),
        DataPoint( new joda.DateTime( 456 ), 7.71 ),
        DataPoint( new joda.DateTime( 457 ), 8.14 ),
        DataPoint( new joda.DateTime( 458 ), 8.14 ),
        DataPoint( new joda.DateTime( 459 ), 7.1 ),
        DataPoint( new joda.DateTime( 460 ), 7.5 ),
        DataPoint( new joda.DateTime( 461 ), 7.1 ),
        DataPoint( new joda.DateTime( 462 ), 7.1 ),
        DataPoint( new joda.DateTime( 463 ), 7.3 ),
        DataPoint( new joda.DateTime( 464 ), 7.71 ),
        DataPoint( new joda.DateTime( 465 ), 8.8 ),
        DataPoint( new joda.DateTime( 466 ), 8.9 )
      )

      val series = TimeSeries( "series", myPoints )

      val algProps = ConfigFactory.parseString(
        s"""
           |${algoS}.seedEps: 5.0
           |${algoS}.minDensityConnectedPoints: 3
        """.stripMargin
      )

      analyzer.receive( DetectionAlgorithmRouter.AlgorithmRegistered( algoS ) )
      implicit val sender = aggregator.ref
      analyzer ! DetectUsing(
        plan.id,
        algoS,
        DetectOutliersInSeries( series, plan, Option( subscriber.ref ), Set.empty[WorkId] ),
        HistoricalStatistics( 2, false ),
        algProps
      )

      aggregator.expectMsgPF( 2.seconds.dilated, "detect" ) {
        //todo stream envelope
        //        case Envelope(SeriesOutliers(alg, source, outliers), hdr) => {
        //          alg must equal( 'dbscan )
        //          source mustBe series
        //          outliers.size mustBe 6
        //        }
        case m @ NoOutliers( alg, source, plan, control ) ⇒ {
          alg mustBe Set( algoS )
          source mustBe series
          m.hasAnomalies mustBe false
        }
      }
    }

    //    "series centroid analyzer doesn't recognize cohort" in { f: Fixture =>
    //      import f._
    //      val analyzer = TestActorRef[SeriesCentroidDensityAnalyzer]( SeriesCentroidDensityAnalyzer.props(router.ref) )
    //      analyzer.receive( DetectionAlgorithmRouter.AlgorithmRegistered( algoS ) )
    //
    //      val series1 = TimeSeries( "series.one", pointsA )
    //      val range = (0.99998, 1.00003)
    //      val series2 = tweakSeries( TimeSeries("series.two", pointsB), range )
    //      val series3 = tweakSeries( TimeSeries("series.three", pointsB), range )
    //      val series4 = tweakSeries( TimeSeries("series.four", pointsB), range )
    //      val cohort = TimeSeriesCohort( series1, series2, series3, series4 )
    //
    //      val algProps = ConfigFactory.parseString(
    //        s"""
    //           |${algoC.name}.seedEps: 5.0
    //           |${algoC.name}.minDensityConnectedPoints: 2
    //        """.stripMargin
    //      )
    //
    //      val expected = DetectUsing(
    //        algoC,
    //        DetectOutliersInCohort(cohort, plan, subscriber.ref, Set()),
    //        HistoricalStatistics(2, false),
    //        algProps
    //      )
    //      implicit val sender = aggregator.ref
    //      analyzer ! expected
    //      aggregator.expectMsgPF( 2.seconds.dilated, "detect" ) {
    //        case UnrecognizedPayload( alg, actual ) => {
    //          alg.name mustBe algoS
    //          actual mustBe expected
    //        }
    //      }
    //    }

    //    "handle no clusters found" in { f: Fixture => pending }
  }
}

object SeriesCentroidDensityAnalyzerSpec {
  val sysId = new AtomicInteger()

  val points = Seq(
    DataPoint( new joda.DateTime( 440 ), 9.46 ),
    DataPoint( new joda.DateTime( 441 ), 9.9 ),
    DataPoint( new joda.DateTime( 442 ), 11.6 ),
    DataPoint( new joda.DateTime( 443 ), 14.5 ),
    DataPoint( new joda.DateTime( 444 ), 17.3 ),
    DataPoint( new joda.DateTime( 445 ), 19.2 ),
    DataPoint( new joda.DateTime( 446 ), 18.4 ),
    DataPoint( new joda.DateTime( 447 ), 14.5 ),
    DataPoint( new joda.DateTime( 448 ), 12.2 ),
    DataPoint( new joda.DateTime( 449 ), 10.8 ),
    DataPoint( new joda.DateTime( 450 ), 8.58 ),
    DataPoint( new joda.DateTime( 451 ), 8.36 ),
    DataPoint( new joda.DateTime( 452 ), 8.58 ),
    DataPoint( new joda.DateTime( 453 ), 7.5 ),
    DataPoint( new joda.DateTime( 454 ), 7.1 ),
    DataPoint( new joda.DateTime( 455 ), 7.3 ),
    DataPoint( new joda.DateTime( 456 ), 7.71 ),
    DataPoint( new joda.DateTime( 457 ), 8.14 ),
    DataPoint( new joda.DateTime( 458 ), 8.14 ),
    DataPoint( new joda.DateTime( 459 ), 7.1 ),
    DataPoint( new joda.DateTime( 460 ), 7.5 ),
    DataPoint( new joda.DateTime( 461 ), 7.1 ),
    DataPoint( new joda.DateTime( 462 ), 7.1 ),
    DataPoint( new joda.DateTime( 463 ), 7.3 ),
    DataPoint( new joda.DateTime( 464 ), 7.71 ),
    DataPoint( new joda.DateTime( 465 ), 8.8 ),
    DataPoint( new joda.DateTime( 466 ), 9.9 ),
    DataPoint( new joda.DateTime( 467 ), 14.2 ),
    DataPoint( new joda.DateTime( 468 ), 18.8 ),
    DataPoint( new joda.DateTime( 469 ), 25.2 ),
    DataPoint( new joda.DateTime( 470 ), 31.5 ),
    DataPoint( new joda.DateTime( 471 ), 22 ),
    DataPoint( new joda.DateTime( 472 ), 24.1 ),
    DataPoint( new joda.DateTime( 473 ), 39.2 )
  )

  val pointsA = Seq(
    DataPoint( new joda.DateTime( 440 ), 9.46 ),
    DataPoint( new joda.DateTime( 441 ), 9.9 ),
    DataPoint( new joda.DateTime( 442 ), 11.6 ),
    DataPoint( new joda.DateTime( 443 ), 14.5 ),
    DataPoint( new joda.DateTime( 444 ), 17.3 ),
    DataPoint( new joda.DateTime( 445 ), 19.2 ),
    DataPoint( new joda.DateTime( 446 ), 18.4 ),
    DataPoint( new joda.DateTime( 447 ), 14.5 ),
    DataPoint( new joda.DateTime( 448 ), 12.2 ),
    DataPoint( new joda.DateTime( 449 ), 10.8 ),
    DataPoint( new joda.DateTime( 450 ), 8.58 ),
    DataPoint( new joda.DateTime( 451 ), 8.36 ),
    DataPoint( new joda.DateTime( 452 ), 8.58 ),
    DataPoint( new joda.DateTime( 453 ), 7.5 ),
    DataPoint( new joda.DateTime( 454 ), 7.1 ),
    DataPoint( new joda.DateTime( 455 ), 7.3 ),
    DataPoint( new joda.DateTime( 456 ), 7.71 ),
    DataPoint( new joda.DateTime( 457 ), 8.14 ),
    DataPoint( new joda.DateTime( 458 ), 8.14 ),
    DataPoint( new joda.DateTime( 459 ), 7.1 ),
    DataPoint( new joda.DateTime( 460 ), 7.5 ),
    DataPoint( new joda.DateTime( 461 ), 7.1 ),
    DataPoint( new joda.DateTime( 462 ), 7.1 ),
    DataPoint( new joda.DateTime( 463 ), 7.3 ),
    DataPoint( new joda.DateTime( 464 ), 7.71 ),
    DataPoint( new joda.DateTime( 465 ), 8.8 ),
    DataPoint( new joda.DateTime( 466 ), 9.9 ),
    DataPoint( new joda.DateTime( 467 ), 14.2 )
  )

  val pointsB = Seq(
    DataPoint( new joda.DateTime( 440 ), 10.1 ),
    DataPoint( new joda.DateTime( 441 ), 10.1 ),
    DataPoint( new joda.DateTime( 442 ), 9.68 ),
    DataPoint( new joda.DateTime( 443 ), 9.46 ),
    DataPoint( new joda.DateTime( 444 ), 10.3 ),
    DataPoint( new joda.DateTime( 445 ), 11.6 ),
    DataPoint( new joda.DateTime( 446 ), 13.9 ),
    DataPoint( new joda.DateTime( 447 ), 13.9 ),
    DataPoint( new joda.DateTime( 448 ), 12.5 ),
    DataPoint( new joda.DateTime( 449 ), 11.9 ),
    DataPoint( new joda.DateTime( 450 ), 12.2 ),
    DataPoint( new joda.DateTime( 451 ), 13 ),
    DataPoint( new joda.DateTime( 452 ), 13.3 ),
    DataPoint( new joda.DateTime( 453 ), 13 ),
    DataPoint( new joda.DateTime( 454 ), 12.7 ),
    DataPoint( new joda.DateTime( 455 ), 11.9 ),
    DataPoint( new joda.DateTime( 456 ), 13.3 ),
    DataPoint( new joda.DateTime( 457 ), 12.5 ),
    DataPoint( new joda.DateTime( 458 ), 11.9 ),
    DataPoint( new joda.DateTime( 459 ), 11.6 ),
    DataPoint( new joda.DateTime( 460 ), 10.5 ),
    DataPoint( new joda.DateTime( 461 ), 10.1 ),
    DataPoint( new joda.DateTime( 462 ), 9.9 ),
    DataPoint( new joda.DateTime( 463 ), 9.68 ),
    DataPoint( new joda.DateTime( 464 ), 9.68 ),
    DataPoint( new joda.DateTime( 465 ), 9.9 ),
    DataPoint( new joda.DateTime( 466 ), 10.8 ),
    DataPoint( new joda.DateTime( 467 ), 11 )
  )

}

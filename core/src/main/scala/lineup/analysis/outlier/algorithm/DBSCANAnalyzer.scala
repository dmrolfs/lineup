package lineup.analysis.outlier.algorithm

import akka.actor.{ ActorRef, Props }
import akka.event.LoggingReceive
import scalaz._, Scalaz._
import org.apache.commons.math3.linear.MatrixUtils
import org.apache.commons.math3.ml.distance.DistanceMeasure
import org.apache.commons.math3.stat.{ descriptive => stat }
import org.apache.commons.math3.ml
import com.typesafe.config.Config
import peds.commons.V
import peds.commons.math.MahalanobisDistance
import lineup.model.timeseries.{ Matrix, DataPoint }
import lineup.model.outlier.{ CohortOutliers, NoOutliers, SeriesOutliers }
import lineup.analysis.outlier.{ HistoricalStatistics, DetectUsing, DetectOutliersInSeries, DetectOutliersInCohort }


/**
 * Created by rolfsd on 9/29/15.
 */
object DBSCANAnalyzer {
  def props( router: ActorRef ): Props = Props( new DBSCANAnalyzer( router ) )

  val Algorithm = 'dbscan

  val Eps = Algorithm.name + ".eps"
  val MinDensityConnectedPoints = Algorithm.name + ".minDensityConnectedPoints"
  val Distance = Algorithm.name + ".distance"
}

class DBSCANAnalyzer( override val router: ActorRef ) extends AlgorithmActor {
  import DBSCANAnalyzer._

  override val algorithm: Symbol = DBSCANAnalyzer.Algorithm

  override def receive: Receive = around( quiescent )

  override val detect: Receive = LoggingReceive {
    case s @ DetectUsing( _, aggregator, payload: DetectOutliersInSeries, history, algorithmConfig ) => {
      val points = payload.data.points map { DataPoint.toDoublePoint }
      val clusters = cluster( points, history, algorithmConfig )
      val isOutlier = makeOutlierTest( clusters )
      val outliers = payload.data.points collect { case dp if isOutlier( dp ) => dp }
      val result = {
        if ( outliers.nonEmpty ) SeriesOutliers( algorithms = Set(algorithm), source = payload.data, outliers = outliers )
        else NoOutliers( algorithms = Set(algorithm), source = payload.data )
      }

      aggregator ! result
    }

    case c @ DetectUsing( algo, aggregator, payload: DetectOutliersInCohort, history, algorithmConfig ) => {
      def cohortDistances( underlying: DetectOutliersInCohort ): Matrix[DataPoint] = {
        for {
          frame <- underlying.data.toMatrix
          timestamp = frame.head._1 // all of frame's _1 better be the same!!!
          frameValues = frame map { _._2 }
          stats = new stat.DescriptiveStatistics( frameValues.toArray )
          median = stats.getPercentile( 50 )
        } yield frameValues map { v => DataPoint( timestamp, v - median ) }
      }

      val outlierMarks = for {
        frameDistances <- cohortDistances( payload )
        points = frameDistances map { dp => new ml.clustering.DoublePoint( Array(dp.timestamp.getMillis.toDouble, dp.value) ) }
        clusters = cluster( points, history, algorithmConfig )
        isOutlier = makeOutlierTest( clusters )
      } yield frameDistances.zipWithIndex collect { case (fd, i) if isOutlier( fd ) => i }

      val result = if ( outlierMarks.isEmpty ) NoOutliers( algorithms = Set(algorithm), source = payload.data )
      else {
        val outlierSeries = outlierMarks.flatten.toSet[Int] map { payload.data.data( _ ) }
        if ( outlierSeries.isEmpty ) NoOutliers( algorithms = Set(algorithm), source = payload.data )
        else CohortOutliers( algorithms = Set(algorithm), source = payload.data, outliers = outlierSeries )
      }

      aggregator ! result

//todo: consider sensitivity here and in series!!!
// DataDog: We use a simplified form of DBSCAN to detect outliers on time series. We consider each host to be a point in
// d-dimensions, where d is the number of elements in the time series. Any point can agglomerate, and any point that is not in
// the largest cluster will be considered an outlier.
//
// The only parameter we take is tolerance, the constant by which the initial threshold is multiplied to yield DBSCAN’s
// distance parameter 𝜀. Here is DBSCAN with a tolerance of 3.0 in action on a pool of Cassandra workers:
    }
  }

  def makeOutlierTest( clusters: Seq[ml.clustering.Cluster[ml.clustering.DoublePoint]] ): DataPoint => Boolean = {
    import scala.collection.JavaConversions._

    if ( clusters.isEmpty ) (pt: DataPoint) => { false }
    else {
      val largestCluster = clusters.maxBy( _.getPoints.size ).getPoints.toSet
      ( pt: DataPoint ) => { !largestCluster.contains( pt ) }
    }
  }

  def cluster(
    payload: Seq[ml.clustering.DoublePoint],
    history: Option[HistoricalStatistics],
    algorithmConfig: Config
  ): Seq[ml.clustering.Cluster[ml.clustering.DoublePoint]] = {
    implicit val algo = algorithm
    val eps = algorithmConfig.getDouble( Eps )
    val minDensityConnectedPoints = algorithmConfig.getInt( MinDensityConnectedPoints )

    import scala.collection.JavaConversions._
    val distance = distanceMeasure( algorithmConfig, history, payload )
    val transformer = new ml.clustering.DBSCANClusterer[ml.clustering.DoublePoint]( eps, minDensityConnectedPoints, distance )
    transformer.cluster( payload )
  }

  def distanceMeasure(
    algorithmConfig: Config,
    history: Option[HistoricalStatistics],
    payload: Seq[ml.clustering.DoublePoint]
  ): DistanceMeasure = {
    def makeMahalanobis(): DistanceMeasure = {
      history map { h =>
        MahalanobisDistance fromCovariance h.covariance
      } getOrElse {
        MahalanobisDistance.fromPoints( MatrixUtils.createRealMatrix( payload.toArray map { _.getPoint } ) )
      } match {
        case Success(result) => result
        case Failure(exs) => throw exs.head
      }
    }

    if ( algorithmConfig hasPath Distance ) {
      algorithmConfig.getString( Distance ).toLowerCase match {
        case "euclidean" | "euclid" =>  new ml.distance.EuclideanDistance
        case "mahalanobis" | "mahal" | _ => makeMahalanobis()
      }
    } else {
      makeMahalanobis()
    }
  }
}

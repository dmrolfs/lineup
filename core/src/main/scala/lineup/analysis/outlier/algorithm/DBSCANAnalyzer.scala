package lineup.analysis.outlier.algorithm

import akka.actor.{ ActorRef, Props }
import akka.event.LoggingReceive
import org.apache.commons.math3.stat.{ descriptive => stat }
import org.apache.commons.math3.ml
import com.typesafe.config.Config
import peds.commons.log.Trace
import peds.akka.envelope._
import lineup.model.timeseries.{ Matrix, DataPoint }
import lineup.model.outlier.{ CohortOutliers, NoOutliers, SeriesOutliers }
import lineup.analysis.outlier.{ DetectUsing, DetectOutliersInSeries, DetectOutliersInCohort }


/**
 * Created by rolfsd on 9/29/15.
 */
object DBSCANAnalyzer {
  def props( router: ActorRef ): Props = Props( new DBSCANAnalyzer( router ) )

  val Algorithm = 'dbscan

  val Eps = Algorithm.name + ".eps"
  val MinDensityConnectedPoints = Algorithm.name + ".minDensityConnectedPoints"

  val DefaultDistanceMeasure: ml.distance.DistanceMeasure = new ml.distance.EuclideanDistance
}

class DBSCANAnalyzer( override val router: ActorRef ) extends AlgorithmActor {
  import DBSCANAnalyzer._
  override val trace: Trace[_] = Trace[DBSCANAnalyzer]
  override val algorithm: Symbol = DBSCANAnalyzer.Algorithm

  override val detect: Receive = LoggingReceive {
    case s @ DetectUsing( _, aggregator, payload: DetectOutliersInSeries, algorithmConfig ) => {
      def pointsFromSeries( underlying: DetectOutliersInSeries ): Seq[ml.clustering.DoublePoint] = {
        underlying.data.points map { dp => new ml.clustering.DoublePoint( Array(dp.timestamp.getMillis.toDouble, dp.value) )}
      }

      val clusters = cluster( pointsFromSeries(payload), algorithmConfig )
      val isOutlier = outlierTest( clusters )
      val outliers = payload.data.points collect { case dp if isOutlier( dp ) => dp }
      val result = {
        if ( outliers.nonEmpty ) SeriesOutliers( algorithms = Set(algorithm), source = payload.data, outliers = outliers )
        else NoOutliers( algorithms = Set(algorithm), source = payload.data )
      }

//todo stream enveloping: aggregator !+ result
      aggregator ! result
    }

    case c @ DetectUsing( algo, aggregator, payload: DetectOutliersInCohort, algorithmConfig ) => {
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
        clusters = cluster( points, algorithmConfig )
        isOutlier = outlierTest( clusters )
      } yield frameDistances.zipWithIndex collect { case (fd, i) if isOutlier( fd ) => i }

      val result = if ( outlierMarks.isEmpty ) NoOutliers( algorithms = Set(algorithm), source = payload.data )
      else {
        val outlierSeries = outlierMarks.flatten.toSet[Int] map { payload.data.data( _ ) }
        if ( outlierSeries.isEmpty ) NoOutliers( algorithms = Set(algorithm), source = payload.data )
        else CohortOutliers( algorithms = Set(algorithm), source = payload.data, outliers = outlierSeries )
      }

//todo stream enveloping: aggregator !+ result
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

  def cluster(
    payload: Seq[ml.clustering.DoublePoint],
    algorithmConfig: Config
  ): Seq[ml.clustering.Cluster[ml.clustering.DoublePoint]] = {
    implicit val algo = algorithm
    val eps = algorithmConfig.getDouble( Eps )
    val minDensityConnectedPoints = algorithmConfig.getInt( MinDensityConnectedPoints )

    import scala.collection.JavaConversions._
    val transformer = new ml.clustering.DBSCANClusterer[ml.clustering.DoublePoint]( eps, minDensityConnectedPoints, DefaultDistanceMeasure )
    transformer.cluster( payload )
  }

  def outlierTest( clusters: Seq[ml.clustering.Cluster[ml.clustering.DoublePoint]] ): DataPoint => Boolean = {
    import scala.collection.JavaConversions._

    if ( clusters.isEmpty ) (pt: DataPoint) => { false }
    else {
      val largestCluster = clusters.maxBy( _.getPoints.size ).getPoints.toSet
      ( pt: DataPoint ) => { !largestCluster.contains( pt ) }
    }
  }
}

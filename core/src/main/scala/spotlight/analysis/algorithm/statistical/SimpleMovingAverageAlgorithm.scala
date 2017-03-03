package spotlight.analysis.algorithm.statistical

import com.persist.logging._
import org.apache.commons.math3.ml.clustering.DoublePoint
import org.apache.commons.math3.stat.descriptive.SummaryStatistics
import spotlight.analysis.DetectUsing
import spotlight.analysis.algorithm.{ Algorithm, CommonContext }
import spotlight.model.timeseries._
import squants.information.{ Bytes, Information }

/** Created by rolfsd on 6/8/16.
  */
object SimpleMovingAverageAlgorithm extends Algorithm[SummaryStatistics]( label = "simple-moving-average" ) { algorithm ⇒
  override type Context = CommonContext
  override def makeContext( message: DetectUsing, state: Option[State] ): Context = new CommonContext( message )

  override def step( point: PointT, shape: Shape )( implicit s: State, c: Context ): Option[( Boolean, ThresholdBoundary )] = {
    val mean = shape.getMean
    val stddev = shape.getStandardDeviation
    val threshold = ThresholdBoundary.fromExpectedAndDistance(
      timestamp = point.timestamp.toLong,
      expected = mean,
      distance = c.tolerance * stddev
    )

    def property( path: String ): String = if ( c.properties hasPath path ) c.properties getString path else "-nil-"

    log.debug(
      Map(
        "@msg" → "Step",
        "config" → Map( "tail" → property( "tail-average" ), "tolerance" → property( "tolerance" ), "minimum-population" → property( "minimum-population" ) ),
        "stats" → Map( "mean" → f"${mean}%2.5f", "standard-deviation" → f"${stddev}%2.5f", "distance" → f"${c.tolerance * stddev}%2.5f" ),
        "point" → Map( "timestamp" → point.dateTime.toString, "value" → f"${point.value}%2.5f" ),
        "threshold" → Map( "1_floor" → threshold.floor.toString, "2_expected" → threshold.expected.toString, "3_ceiling" → threshold.ceiling.toString ),
        "is-anomaly" → threshold.isOutlier( point.value )
      )
    )

    Some( ( threshold isOutlier point.value, threshold ) )
  }

  /** Optimization available for algorithms to more efficiently respond to size estimate requests for algorithm sharding.
    * @return blended average size for the algorithm shape
    */
  override val estimatedAverageShapeSize: Option[Information] = Some( Bytes( 345 ) )
}

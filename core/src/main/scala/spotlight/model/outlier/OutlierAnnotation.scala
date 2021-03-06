package spotlight.model.outlier

import cats.instances.list._
import cats.instances.either._
import cats.syntax.either._
import cats.syntax.validated._
import cats.syntax.traverse._
import org.joda.{ time ⇒ joda }
import com.github.nscala_time.time.Imports._
import omnibus.commons.{ AllIssuesOr, ErrorOr }

trait OutlierAnnotation {
  def start: joda.DateTime
  def end: Option[joda.DateTime]
}

object OutlierAnnotation {
  def outlierAnnotation( obm: OutlierBoundsMagnet ): AllIssuesOr[OutlierAnnotation] = { obm() }

  def cleanAnnotation( start: Option[joda.DateTime], end: Option[joda.DateTime] ): AllIssuesOr[OutlierAnnotation] = {
    check( start, end ) map { i ⇒ NoOutlierIntervalAnnotation( i ) }
  }

  def annotationsFromSeries( series: Outliers ): AllIssuesOr[Seq[OutlierAnnotation]] = {
    val result = series match {
      case no: NoOutliers ⇒ List( cleanAnnotation( no.source.start, no.source.end ) )

      case tso: SeriesOutliers if tso.hasAnomalies == false ⇒ List( cleanAnnotation( tso.source.start, tso.source.end ) )

      case tso: SeriesOutliers ⇒ {
        for {
          g ← tso.anomalousGroups.toList
          start = g.keySet.min
          end = g.keySet.max
        } yield outlierAnnotation( start to end )
      }
    }

    result.sequence map { _.toSeq }
  }

  sealed trait OutlierBoundsMagnet {
    type Result = AllIssuesOr[OutlierAnnotation]
    def apply(): Result
  }

  implicit def fromInstant( instant: joda.DateTime ): OutlierBoundsMagnet = new OutlierBoundsMagnet {
    override def apply(): Result = check map { i ⇒ OutlierInstantAnnotation( i ) }
    val check: AllIssuesOr[joda.DateTime] = instant.validNel
    override def toString: String = s"fromInstant($instant)"
  }

  implicit def fromInterval( interval: joda.Interval ): OutlierBoundsMagnet = new OutlierBoundsMagnet {
    override def apply(): Result = check( Some( interval.getStart ), Some( interval.getEnd ) ) map { i ⇒ OutlierIntervalAnnotation( i ) }
    override def toString: String = s"fromInterval($interval)"
  }

  type FlexInterval = ( Option[joda.DateTime], Option[joda.DateTime] )

  implicit def fromUncertainInterval( start: Option[joda.DateTime], end: Option[joda.DateTime] ) = {
    new OutlierBoundsMagnet {
      override def apply(): Result = {
        check
          .flatMap { flex ⇒
            flex match {
              case ( None, None ) ⇒ OutlierTimeBoundsError( None, None ).asLeft
              case ( Some( start ), None ) ⇒ OutlierUnboundedAnnotaion( start ).asRight
              case interval @ ( None, Some( _ ) ) ⇒ OutlierTimeBoundsError( interval ).asLeft
              case ( Some( s ), Some( e ) ) if s isEqual e ⇒ OutlierInstantAnnotation( s ).asRight
              case interval @ ( Some( s ), Some( e ) ) if s isAfter e ⇒ OutlierTimeBoundsError( interval ).asLeft
              case ( Some( start ), Some( end ) ) ⇒ OutlierIntervalAnnotation( start to end ).asRight
            }
          }
          .toValidatedNel
      }

      val check: ErrorOr[FlexInterval] = {
        ( start, end ) match {
          case ( None, None ) ⇒ OutlierTimeBoundsError( None, None ).asLeft
          case interval @ ( Some( _ ), None ) ⇒ interval.asRight
          case interval @ ( None, Some( _ ) ) ⇒ interval.asRight
          case interval @ ( Some( s ), Some( e ) ) if s isAfter e ⇒ OutlierTimeBoundsError( interval ).asLeft
          case interval @ ( Some( _ ), Some( _ ) ) ⇒ interval.asRight
        }
      }

      override def toString: String = s"fromUncertainInterval($start, $end)"
    }
  }

  private def check( start: Option[joda.DateTime], end: Option[joda.DateTime] ): AllIssuesOr[joda.Interval] = {
    val result = for {
      s ← start
      e ← end
    } yield {
      if ( s isAfter e ) OutlierTimeBoundsError( Some( s ), Some( e ) ).invalidNel
      else ( s to e ).validNel
    }

    result getOrElse OutlierTimeBoundsError( start, end ).invalidNel
  }

  /** An outlier annotation that describes a single outlier observation. This annotation directly reflects an additive outlier.
    * For example, a data coding error might be identified as an additive outlier.
    * @param instant point in time of the outlier
    */
  final case class OutlierInstantAnnotation private[outlier] ( instant: joda.DateTime ) extends OutlierAnnotation {
    override val start: joda.DateTime = instant
    override val end: Option[joda.DateTime] = None
  }

  /** An outlier annotation that describes outlier data that spans a known time period. This annotation directly reflects an
    * innovation outlier, which acts as an addition to the noise term at a particular series point. For stationary series, an
    * innovational outlier affects several observations.
    * @param interval time interval over which outlier innovative outlier exists
    */
  final case class OutlierIntervalAnnotation private[outlier] ( interval: joda.Interval ) extends OutlierAnnotation {
    override val start: joda.DateTime = interval.getStart
    override val end: Option[joda.DateTime] = Some( interval.getEnd )
  }

  /** An outlier annotation that describes outlier data whose end is unknown. This annotation may reflect the beginning of an
    * innovation outlier that spans time series or a level shift outlier.
    * @param start point in time beginning the outlier
    */
  final case class OutlierUnboundedAnnotaion private[outlier] ( override val start: joda.DateTime ) extends OutlierAnnotation {
    override val end: Option[joda.DateTime] = None
  }

  /** An annotation that describes an interval time period without outliers. This annotation may be used to terminate an
    * outlier period started by an unbounded annotation or otherwise mark a period without outliers.
    * @param interval time interval during which no outliers exist
    */
  final case class NoOutlierIntervalAnnotation private[outlier] ( interval: joda.Interval ) extends OutlierAnnotation {
    override val start: joda.DateTime = interval.getStart
    override val end: Option[joda.DateTime] = Some( interval.getEnd )
  }

  final case class OutlierTimeBoundsError private[outlier] ( interval: FlexInterval )
    extends IllegalArgumentException(
      s"""start [${interval._1 getOrElse "None"}] cannot be after end [${interval._2 getOrElse "None"}]"""
    ) with OutlierError
}

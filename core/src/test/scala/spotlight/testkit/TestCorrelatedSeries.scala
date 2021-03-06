package spotlight.testkit

import omnibus.akka.envelope.WorkId
import spotlight.model.outlier.{ CorrelatedData, AnalysisPlan, _ }
import spotlight.model.timeseries.TimeSeries

/** Created by rolfsd on 11/4/16.
  */
case class TestCorrelatedSeries(
    override val data: TimeSeries,
    override val correlationIds: Set[WorkId] = Set.empty[WorkId],
    override val scope: Option[AnalysisPlan.Scope] = None
) extends CorrelatedSeries {
  override def withData( newData: TimeSeries ): CorrelatedData[TimeSeries] = copy( data = newData )
  override def withCorrelationIds( newIds: Set[WorkId] ): CorrelatedData[TimeSeries] = copy( correlationIds = newIds )
  override def withScope( newScope: Option[AnalysisPlan.Scope] ): CorrelatedData[TimeSeries] = copy( scope = newScope )
}

package spotlight.analysis.outlier

import scala.reflect._
import akka.actor.{ActorContext, ActorRef, PoisonPill, Props, Terminated}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import demesne.module.entity.EntityAggregateModule
import demesne.module.entity.EntityAggregateModule.MakeIndexSpec
import demesne.module.entity.{messages => EntityMessages}
import demesne.index.local.IndexLocalAgent
import demesne.{AggregateProtocol, AggregateRootType, DomainModel}
import demesne.index.{Directive, IndexBusSubscription, StackableIndexBusPublisher}
import peds.akka.envelope._
import peds.akka.publish.{EventPublisher, StackableStreamPublisher}
import peds.archetype.domain.model.core.{EntityIdentifying, EntityLensProvider}
import peds.commons.identifier.ShortUUID
import shapeless.Lens
import spotlight.model.outlier.{IsQuorum, OutlierPlan, ReduceOutliers}
import spotlight.model.timeseries.{TimeSeries, Topic}


object AnalysisPlanProtocol extends AggregateProtocol[OutlierPlan#ID]{
  sealed trait AnalysisPlanMessage
  sealed abstract class AnalysisPlanCommand extends AnalysisPlanMessage with CommandMessage
  sealed abstract class AnalysisPlanEvent extends AnalysisPlanMessage with EventMessage

  case class AcceptTimeSeries( override val targetId: AcceptTimeSeries#TID, data: TimeSeries ) extends AnalysisPlanCommand


  //todo add info change commands
  //todo reify algorithm
  //      case class AddAlgorithm( override val targetId: OutlierPlan#TID, algorithm: Symbol ) extends Command with AnalysisPlanMessage
  case class ApplyTo( override val targetId: ApplyTo#TID, appliesTo: OutlierPlan.AppliesTo ) extends AnalysisPlanCommand

  case class UseAlgorithms(
    override val targetId: UseAlgorithms#TID,
    algorithms: Set[Symbol],
    algorithmConfig: Config
  ) extends AnalysisPlanCommand

  case class ResolveVia(
    override val targetId: ResolveVia#TID,
    isQuorum: IsQuorum,
    reduce: ReduceOutliers
  ) extends AnalysisPlanCommand


  case class ScopeChanged( override val sourceId: ScopeChanged#TID, appliesTo: OutlierPlan.AppliesTo ) extends AnalysisPlanEvent

  case class AlgorithmsChanged(
    override val sourceId: AlgorithmsChanged#TID,
    algorithms: Set[Symbol],
    algorithmConfig: Config
  ) extends AnalysisPlanEvent

  case class AnalysisResolutionChanged(
    override val sourceId: AnalysisResolutionChanged#TID,
    isQuorum: IsQuorum,
    reduce: ReduceOutliers
  ) extends AnalysisPlanEvent

  case class GetPlan( override val targetId: GetPlan#TID ) extends AnalysisPlanCommand
  case class PlanInfo( override val sourceId: PlanInfo#TID, info: OutlierPlan ) extends AnalysisPlanEvent {
    def toSummary: OutlierPlan.Summary = OutlierPlan.Summary( id = sourceId, name = info.name, appliesTo = info.appliesTo )
  }

  private[outlier] case class GetProxies( override val targetId: GetProxies#TID ) extends AnalysisPlanCommand
  private[outlier] case class Proxies(
    override val sourceId: Proxies#TID,
    scopeProxies: Map[Topic, ActorRef]
  ) extends AnalysisPlanEvent
}


/**
  * Created by rolfsd on 5/26/16.
  */
object AnalysisPlanModule extends EntityLensProvider[OutlierPlan] with LazyLogging {
  implicit val identifying: EntityIdentifying[OutlierPlan] = {
    new EntityIdentifying[OutlierPlan] with ShortUUID.ShortUuidIdentifying[OutlierPlan] {
      override val evEntity: ClassTag[OutlierPlan] = classTag[OutlierPlan]
    }
  }


  override def idLens: Lens[OutlierPlan, OutlierPlan#TID] = OutlierPlan.idLens
  override def nameLens: Lens[OutlierPlan, String] = OutlierPlan.nameLens
  override def slugLens: Lens[OutlierPlan, String] = OutlierPlan.slugLens

  val namedPlanIndex: Symbol = 'NamedPlan

  val indexes: MakeIndexSpec = {
    () => {
      Seq(
        IndexLocalAgent.spec[String, module.TID, OutlierPlan.Summary]( specName = namedPlanIndex, IndexBusSubscription ) {
          case EntityMessages.Added( sid, Some( p: OutlierPlan ) ) => Directive.Record( p.name, sid, p.toSummary )
          case EntityMessages.Added( sid, info ) => {
            logger.error( "AnalysisPlanModule: IGNORING ADDED event since info was not Some OutlierPlan: [{}]", info )
            Directive.Ignore
          }
          case EntityMessages.Disabled( sid, _ ) => Directive.Withdraw( sid )
          case EntityMessages.Renamed( sid, oldName, newName ) => Directive.ReviseKey( oldName, newName )
          case m: EntityMessages.EntityMessage => Directive.Ignore
          case m: AnalysisPlanProtocol.AnalysisPlanMessage => Directive.Ignore
        }
      )
    }
  }

  val module: EntityAggregateModule[OutlierPlan] = {
    val b = EntityAggregateModule.builderFor[OutlierPlan].make
    import b.P.{ Tag => BTag, Props => BProps, _ }

    b
    .builder
    .set( BTag, identifying.idTag )
    .set( BProps, AggregateRoot.OutlierPlanActor.props(_, _) )
    .set( Indexes, indexes )
    .set( IdLens, OutlierPlan.idLens )
    .set( NameLens, OutlierPlan.nameLens )
    .set( IsActiveLens, Some(OutlierPlan.isActiveLens) )
    .build()
  }


  object AggregateRoot {
    object OutlierPlanActor {
      def props( model: DomainModel, rootType: AggregateRootType ): Props = Props( new AggregateRootActor(model, rootType) )

      private class AggregateRootActor( model: DomainModel, rootType: AggregateRootType )
      extends OutlierPlanActor( model, rootType )
      with ProxyProvider
      with StackableStreamPublisher
      with StackableIndexBusPublisher {
        override protected def onPersistRejected( cause: Throwable, event: Any, seqNr: Long ): Unit = {
          log.error(
            "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
            event.getClass.getName, seqNr, persistenceId, cause
          )
          throw cause
        }
      }


      trait ProxyProvider { provider =>
        def highWatermark: Int = 10 * Runtime.getRuntime.availableProcessors()
        def bufferSize: Int = 1000

        def makeProxy(
          topic: Topic,
          plan: OutlierPlan
        )(
          implicit model: DomainModel,
          context: ActorContext
        ): ActorRef = {
          val scope = OutlierPlan.Scope( plan, topic )
          context.actorOf(
            AnalysisScopeProxy.props(
              scope = scope,
              plan = plan,
              model = model,
              highWatermark = provider.highWatermark,
              bufferSize = provider.bufferSize
            ),
            name = scope.toString+"-AnalysisScopeProxy"
          )
        }
      }
    }

    class OutlierPlanActor( override val model: DomainModel, override val rootType: AggregateRootType )
    extends module.EntityAggregateActor { outer: OutlierPlanActor.ProxyProvider with EventPublisher =>
      import AnalysisPlanProtocol._


      override def preDisable(): Unit = {
        scopeProxies.values foreach { _ ! PoisonPill }
        scopeProxies = Map.empty[Topic, ActorRef]
      }

      override var state: OutlierPlan = _

      var scopeProxies: Map[Topic, ActorRef] = Map.empty[Topic, ActorRef]

      def proxyFor( topic: Topic ): ActorRef = {
        scopeProxies
        .get( topic )
        .getOrElse {
          val proxy = outer.makeProxy( topic, state )( model, context )
          scopeProxies += topic -> proxy
          log.info( "TEST: Setting [{}] to watch proxy:[{}]", self, proxy )
          context watch proxy
          proxy
        }
      }

      override def acceptance: Acceptance = entityAcceptance orElse {
        case (e: ScopeChanged, s) => {
          //todo: cast for expediency. my ideal is to define a Lens in the OutlierPlan trait; minor solace is this module is in the same package
          s.asInstanceOf[OutlierPlan.SimpleOutlierPlan].copy( appliesTo = e.appliesTo )
        }

        case (e: AlgorithmsChanged, s) => {
          //todo: cast for expediency. my ideal is to define a Lens in the OutlierPlan trait; minor solace is this module is in the same package
          s.asInstanceOf[OutlierPlan.SimpleOutlierPlan].copy(
            algorithms = e.algorithms,
            algorithmConfig = e.algorithmConfig
          )
        }

        case (e: AnalysisResolutionChanged, s) =>{
          //todo: cast for expediency. my ideal is to define a Lens in the OutlierPlan trait; minor solace is this module is in the same package
          s.asInstanceOf[OutlierPlan.SimpleOutlierPlan].copy(
            isQuorum = e.isQuorum,
            reduce = e.reduce
          )
        }
      }

      val IdType = identifying.evTID

      import demesne.module.entity.{ messages => EntityMessages }
      override def quiescent: Receive = {
        case EntityMessages.Add( IdType(targetId), info ) if targetId == aggregateId => {
          persist( EntityMessages.Added(targetId, info) ) { e =>
            acceptAndPublish( e )
            sender() !+ e  // per akka docs: safe to use sender() here
          }
        }

        case EntityMessages.Add( targetId, info ) => {
          log.info(
            "AnalysisPlanModule caught Add message but unsure targetId[{}] matches aggregateId:[{}] => [{}][{}]",
            (targetId, targetId.id.getClass),
            (aggregateId, aggregateId.id.getClass),
            targetId == aggregateId,
            targetId.id == aggregateId.id
          )
        }
        case a => {
          log.info( "AnalysisPlanModule caught generic message: [{}]", a )
        }
      }

      override def active: Receive = trace.block( "active" ) {
        workflow orElse maintenance orElse planEntity orElse super.active
      }

      def workflow: Receive = {
        case AcceptTimeSeries( id, ts ) => {
          log.debug(
            "AnalysisPlanModule[{}] routing data for topic:[{}] sender:[{}]",
            s"${self.path.name}:${workId}", ts.topic, sender().path.name
          )
          proxyFor( ts.topic ) forwardEnvelope ( ts, OutlierPlan.Scope(plan = state, topic = ts.topic) )
        }
      }

      def planEntity: Receive = {
        case _: GetPlan => sender() !+ PlanInfo( state.id, state )

        case ApplyTo( id, appliesTo ) => persist( ScopeChanged(id, appliesTo) ) { e => acceptAndPublish( e ) }

        case UseAlgorithms( id, algorithms, config ) => {
          persist( AlgorithmsChanged(id, algorithms, config) ) { e => acceptAndPublish( e ) }
        }

        case ResolveVia( id, isQuorum, reduce ) => {
          persist( AnalysisResolutionChanged(id, isQuorum, reduce) ) { e => acceptAndPublish( e ) }
        }
      }

      def maintenance: Receive = {
        case Terminated( deadActor ) => {
          log.info( "[{}] notified of dead actor at:[{}]", aggregateId, deadActor.path )
          scopeProxies find { case (_, ref) => ref == deadActor } foreach { case (t, _) =>
            log.warning( "removing record of dead proxy for topic:[{}]", t )
            scopeProxies = scopeProxies - t
          }
        }

        case _: GetProxies => sender() ! Proxies( aggregateId, scopeProxies )
      }

      override def unhandled( message: Any ): Unit = {
        val total = active
        log.error(
          "[{}] UNHANDLED: [{}] (workflow,maintenance,planEntity,super):[{}] total:[{}]",
          aggregateId,
          message,
          (
            workflow.isDefinedAt(message),
            maintenance.isDefinedAt(message),
            planEntity.isDefinedAt(message),
            super.active.isDefinedAt(message)
          ),
          total.isDefinedAt(message)
        )
        super.unhandled( message )
      }
    }
  }
}
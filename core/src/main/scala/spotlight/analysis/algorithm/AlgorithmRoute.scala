package spotlight.analysis.algorithm

import akka.actor.{ ActorContext, ActorRef }
import cats.syntax.either._
import cats.syntax.validated._
import com.persist.logging._
import com.typesafe.config._
import shapeless.the
import demesne.{ AggregateRootType, DomainModel }
import net.ceedubs.ficus.Ficus._
import omnibus.akka.envelope._
import omnibus.commons.config._
import omnibus.commons.identifier.Identifying
import omnibus.commons.util._
import spotlight.{ DM, AC }
import spotlight.Settings
import spotlight.analysis.DetectUsing
import spotlight.analysis.shard._
import spotlight.model.outlier.AnalysisPlan
import squants.information.{ Information, Megabytes }

sealed abstract class AlgorithmRoute {
  import AlgorithmRoute.AR

  def forward[_: AR: AC]( message: Any ): Unit = referenceFor( message ) forwardEnvelope message
  def referenceFor[_: AC]( message: Any ): ActorRef
}

object AlgorithmRoute extends ClassLogging {
  import ShardedRoute.Strategy

  type AR[_] = ActorRef

  def routeFor[_: DM]( plan: AnalysisPlan.Summary, algorithmRootType: AggregateRootType ): AlgorithmRoute = {
    val strategy = Strategy from plan

    val route = {
      strategy
        .map { strategy ⇒ ShardedRoute( plan, algorithmRootType, strategy, the[DomainModel] ) }
        .getOrElse { RootTypeRoute( plan, algorithmRootType, the[DomainModel] ) }
    }

    log.debug(
      Map(
        "@msg" → "determined algorithm route",
        "strategy" → strategy.map { _.key }.toString,
        "plan" → plan.name,
        "algorithm" → algorithmRootType.name,
        "route" → route.toString
      )
    )

    route
  }

  case class DirectRoute( reference: ActorRef ) extends AlgorithmRoute {
    override def referenceFor[_: AC]( message: Any ): ActorRef = reference
  }

  case class RootTypeRoute(
      plan: AnalysisPlan.Summary,
      algorithmRootType: AggregateRootType,
      model: DomainModel
  ) extends AlgorithmRoute with ClassLogging with com.typesafe.scalalogging.StrictLogging {
    implicit lazy val algorithmIdentifying: Identifying.Aux[_, Algorithm.ID] = {
      algorithmRootType.identifying.asInstanceOf[Identifying.Aux[_, Algorithm.ID]]
    }

    override def forward[_: AR: AC]( message: Any ): Unit = {
      referenceFor( message ) forwardEnvelope AlgorithmProtocol.RouteMessage( targetId = algorithmIdFor( message ), message )
    }

    def algorithmIdFor( message: Any ): Algorithm.TID = {
      val result = message match {
        case m: DetectUsing ⇒ {
          algorithmIdentifying.tag(
            AlgorithmIdentifier(
              planName = plan.name,
              planId = plan.id.id.toString,
              spanType = AlgorithmIdentifier.TopicSpan,
              span = m.topic.toString
            )
          )
        }

        case m if algorithmRootType.aggregateIdFor isDefinedAt m ⇒ {
          val ( aid, _ ) = algorithmRootType.aggregateIdFor( m )

          AlgorithmIdentifier
            .fromAggregateId( aid )
            .map { pid ⇒ algorithmIdentifying.tag( pid.asInstanceOf[algorithmIdentifying.ID] ) }
            .valueOr { exs ⇒
              exs map { ex ⇒
                log.error(
                  Map(
                    "@msg" → "failed to parse algorithm identifier from aggregate id",
                    "aggregateId" → aid,
                    "message" → m.toString
                  ),
                  ex
                )
              }

              throw exs.head
            }
        }

        case m ⇒ {
          val ex = new IllegalStateException(
            s"failed to extract algorithm[${algorithmRootType.name}] aggregate id from message:[${m}]"
          )

          log.error(
            Map(
              "@msg" → "failed to extract algorithm aggregate id from message",
              "algorithm" → algorithmRootType.name,
              "message-class" → m.getClass.getName
            ),
            ex
          )

          throw ex
        }
      }

      result.asInstanceOf[Algorithm.TID]
    }

    override def referenceFor[_: AC]( message: Any ): ActorRef = {
      Either
        .catchNonFatal { model( algorithmRootType, algorithmIdFor( message ) ) }
        .valueOr { ex ⇒
          log.error(
            Map(
              "@msg" → "failed to find aggregate reference for algorithm id from message - returning dead letter actor",
              "model" → model.toString,
              "algorithm-id" → algorithmIdFor( message ),
              "message-class" → message.getClass.getName
            )
          )
          model.system.deadLetters
        }
    }
  }

  case class ShardedRoute(
      plan: AnalysisPlan.Summary,
      algorithmRootType: AggregateRootType,
      strategy: Strategy,
      implicit val model: DomainModel
  ) extends AlgorithmRoute {
    implicit val scIdentifying: Identifying.Aux[_, ShardCatalog.ID] = strategy.identifying
    val shardingId: ShardCatalog#TID = strategy.idFor( plan, algorithmRootType.name )
    val shardingRef = strategy.actorFor( plan, algorithmRootType )( model )

    override def forward[_: AR: AC]( message: Any ): Unit = {
      referenceFor( message ) forwardEnvelope ShardProtocol.RouteMessage( shardingId, message )
    }

    override def referenceFor[_: AC]( message: Any ): ActorRef = shardingRef
    override def toString: String = {
      s"${getClass.safeSimpleName}( " +
        s"plan:[${plan.name}] " +
        s"algorithmRootType:[${algorithmRootType.name}] " +
        s"strategy:[${strategy}] " +
        s"model:[${model}] )"
    }
  }

  object ShardedRoute {
    sealed trait Strategy {
      val rootType: AggregateRootType
      def key: String
      def makeAddCommand( plan: AnalysisPlan.Summary, algorithmRootType: AggregateRootType ): Option[Any]

      implicit lazy val identifying: Identifying.Aux[_, ShardCatalog.ID] = {
        rootType.identifying.asInstanceOf[Identifying.Aux[_, ShardCatalog.ID]]
      }

      def actorFor[_: DM]( plan: AnalysisPlan.Summary, algorithmRootType: AggregateRootType ): ActorRef = {
        val sid = idFor( plan, algorithmRootType.name )
        val ref = the[DomainModel].apply( rootType, sid )
        val add = makeAddCommand( plan, algorithmRootType )
        add foreach { ref !+ _ }
        ref
      }

      //      def nextAlgorithmId( plan: AnalysisPlan.Summary, algorithmRootType: AggregateRootType ): Algorithm.TID = {
      //        import scala.language.existentials
      //        val identifying = algorithmRootType.identifying.asInstanceOf[Identifying.Aux[_, Algorithm.ID]]
      //        identifying.tag(
      //          AlgorithmIdentifier(
      //            planName = plan.name,
      //            planId = plan.id.id.toString(),
      //            spanType = AlgorithmIdentifier.GroupSpan,
      //            span = ShortUUID().toString()
      //          )
      //        )
      //      }
      //      def nextAlgorithmId( plan: AnalysisPlan.Summary, algorithmRootType: AggregateRootType ): () ⇒ Algorithm.TID = { () ⇒
      //        algorithmRootType.i
      //
      //
      //        algorithmRootType.identifying.tag(
      //          AlgorithmIdentifier(
      //          planName = plan.name,
      //          planId = plan.id.id.toString(),
      //          spanType = AlgorithmIdentifier.GroupSpan,
      //          span = ShortUUID().toString()
      //        ).asInstanceOf[algorithmRootType.identifying.ID]
      //        ).asInstanceOf[Algorithm.TID]
      //      }

      def idFor(
        plan: AnalysisPlan.Summary,
        algorithmLabel: String
      )(
        implicit
        identifying: Identifying.Aux[_, ShardCatalog#ID]
      ): ShardCatalog#TID = {
        identifying.tag( ShardCatalog.ID( plan.id, algorithmLabel ) )
      }
    }

    object Strategy extends ClassLogging {
      val PlanShardPath = "shard"

      type ShardingFactory = Config ⇒ Strategy

      private val DefaultFactory: ShardingFactory = CellStrategy.make
      private def DefaultStrategy: Strategy = DefaultFactory( ConfigFactory.empty )

      private val strategyFactories: Map[String, ShardingFactory] = Map(
        CellStrategy.key → CellStrategy.make,
        LookupStrategy.key → LookupStrategy.make
      )

      def from( plan: AnalysisPlan.Summary )( implicit model: DomainModel ): Option[Strategy] = {
        import shapeless.syntax.typeable._

        import scala.collection.JavaConverters._

        for {
          v ← Settings.detectionPlansConfigFrom( model.configuration ).root.asScala.find { _._1 == plan.name }.map { _._2 }
          co ← v.cast[ConfigObject]
          c = co.toConfig
          shardValue ← c.as[Option[ConfigValue]]( PlanShardPath )
        } yield {
          val DefaultFactory = CellStrategy.make

          shardValue.valueType match {
            case ConfigValueType.STRING ⇒ {
              shardValue
                .unwrapped.cast[String]
                .map { key ⇒ makeStrategy( key ) }
                .getOrElse { DefaultStrategy }
            }

            case ConfigValueType.OBJECT ⇒ {
              import scala.collection.JavaConverters._
              import scala.reflect._

              val ConfigObjectType = classTag[ConfigObject]
              val specs = c.as[Config]( PlanShardPath ).root

              if ( specs.size == 1 ) {
                val ( key, ConfigObjectType( value ) ) = specs.asScala.head
                makeStrategy( key, value.toConfig )
              } else {
                val ex = new IllegalStateException(
                  s"too many shard declarations [${specs.size}] for plan. refer to error log for details"
                )

                log.error(
                  Map(
                    "@msg" → "too many shard declarations for plan",
                    "plan" → plan.name,
                    "origin" → shardValue.origin().toString
                  ),
                  ex
                )

                throw ex
              }
            }

            case t ⇒ {
              val ex = new IllegalStateException( s"invalid shard specification: [${t}" )

              log.error(
                Map(
                  "@msg" → "invalid shard specification - use either string or configuration object",
                  "plan" → plan.name,
                  "origin" → shardValue.origin().toString
                )
              )

              throw ex
            }
          }
        }
      }

      def makeStrategy( key: String, spec: Config = ConfigFactory.empty ): Strategy = {
        strategyFactories.getOrElse( key, DefaultFactory ) apply spec
      }
    }

    object CellStrategy {
      val key: String = "cell"

      val SizePath = "size"
      val DefaultSize: Int = 3000

      val make: ( Config ) ⇒ Strategy = { c ⇒
        val sz = c.as[Option[Int]]( SizePath ) getOrElse DefaultSize
        CellStrategy( nrCells = sz )
      }
    }

    case class CellStrategy( nrCells: Int ) extends Strategy {
      override def key: String = CellStrategy.key
      override val rootType: AggregateRootType = CellShardModule.module.rootType

      override def makeAddCommand( plan: AnalysisPlan.Summary, algorithmRootType: AggregateRootType ): Option[Any] = {
        Some(
          CellShardProtocol.Add(
            targetId = idFor( plan, algorithmRootType.name ),
            plan,
            algorithmRootType,
            nrCells = nrCells,
            idGenerator = AlgorithmIdGenerator( plan.name, plan.id, algorithmRootType )
          )
        )
      }
    }

    object LookupStrategy {
      val key: String = "lookup"

      val ExpectedNrTopicsPath = "expected-topics-nr"
      val DefaultExpectedNrTopics: Int = 3000000

      val ControlBySizePath = "control-by-size"
      val DefaultControlBySize: Information = Megabytes( 3 )

      val make: ( Config ) ⇒ Strategy = { c ⇒
        val topics = c.as[Option[Int]]( ExpectedNrTopicsPath ) getOrElse DefaultExpectedNrTopics
        val bySize = c.as[Option[Information]]( ControlBySizePath ) getOrElse DefaultControlBySize // Bytes( c.getMemorySize( ControlBySizePath ).toBytes ) else DefaultControlBySize
        LookupStrategy( expectedNrTopics = topics, bySize = bySize )
      }
    }

    case class LookupStrategy( expectedNrTopics: Int, bySize: Information ) extends Strategy {
      override def key: String = LookupStrategy.key
      override val rootType: AggregateRootType = LookupShardModule.rootType

      override def makeAddCommand( plan: AnalysisPlan.Summary, algorithmRootType: AggregateRootType ): Option[Any] = {
        Some(
          LookupShardProtocol.Add(
            targetId = idFor( plan, algorithmRootType.name ),
            plan,
            algorithmRootType,
            expectedNrTopics,
            bySize,
            AlgorithmIdGenerator( plan.name, plan.id, algorithmRootType )
          )
        )
      }
    }
  }
}

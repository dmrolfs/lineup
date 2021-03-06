package spotlight.train

import java.io.{ File, OutputStream }
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import org.apache.avro.Schema
import org.apache.avro.file.{ CodecFactory, DataFileWriter, SeekableInput }
import org.apache.avro.generic.{ GenericRecordBuilder, GenericDatumWriter, GenericRecord }
import org.apache.avro.mapred.FsInput
import org.apache.hadoop.conf.{ Configuration => HConfiguration }
import org.apache.hadoop.fs.{ FileSystem, Path }
import com.typesafe.config.{ Config, ConfigFactory }
import omnibus.commons.log.Trace
import spotlight.model.timeseries.{ TimeSeriesCohort, DataPoint }


/**
  * Created by rolfsd on 1/19/16.
  */
class AvroFileTrainingRepositoryInterpreter()(implicit ec: ExecutionContext )
extends TrainingRepository.Interpreter
with AvroFileTrainingRepositoryInterpreter.AvroWriter {
  outer: AvroFileTrainingRepositoryInterpreter.WritersContextProvider =>

  import TrainingRepository._
  import AvroFileTrainingRepositoryInterpreter._

  implicit val pool: ExecutorService = executionContextToService( ec )

  override def step[Next]( action: TrainingProtocolF[TrainingProtocol[Next]] ): Task[TrainingProtocol[Next]] = {
    action match {
      case PutTimeSeries(series, next) => write.run( TimeSeriesCohort(series) ) map { _ => next }
      case PutTimeSeriesCohort(cohort, next) => write.run( cohort ) map { _ => next }
    }
  }

  override val genericRecords: Op[WritersContext, List[GenericRecord]] = {
    Kleisli[Task, WritersContext, List[GenericRecord]] { ctx =>
      import scala.collection.JavaConverters._

      Task {
        val dpSchema = outer.datapointSubSchema( ctx.schema )

        ctx.cohort.data.toList map { ts =>
          val record = new GenericRecordBuilder( ctx.schema )
          record.set( "topic", ts.topic.toString ) // record topic per individual series rt generalized cohort

          val dps = ts.points map { case DataPoint(t, v) =>
            val dp = new GenericRecordBuilder( dpSchema )
            dp.set( "timestamp", t.getMillis )
            dp.set( "value", v )
            dp.build()
          }

          record.set( "points", dps.asJava )
          record.build()
        }
      }
    }
  }


  override val makeWritersContext: Op[TimeSeriesCohort, WritersContext] = {
    Kleisli[Task, TimeSeriesCohort, WritersContext] { cohort => Task { outer.context( cohort ) } }
  }

  override val makeWriter: Op[WritersContext, Writer] = {
    Kleisli[Task, WritersContext, Writer] { ctx  => Task fromDisjunction { ctx.writer } }
  }

  override val writeRecords: Op[RecordsContext, Writer] = {
    Kleisli[Task, RecordsContext, Writer] { case RecordsContext(records, writer) =>
      Task {
        records foreach { r => writer append r }
        writer
      }
    }
  }

  override val closeWriter: Op[Writer, Unit] = Kleisli[Task, Writer, Unit] { writer => Task { writer.close() } }

  private val _timeseriesDatumWriter: GenericDatumWriter[GenericRecord] = {
    new GenericDatumWriter[GenericRecord]( outer.timeseriesSchema )
  }

  private def makeDataWriter: DataFileWriter[GenericRecord] = {
    new DataFileWriter[GenericRecord]( _timeseriesDatumWriter ).setCodec( CodecFactory.snappyCodec )
  }
}


object AvroFileTrainingRepositoryInterpreter {
  val trace = Trace[AvroFileTrainingRepositoryInterpreter.type]

  class WritersContext(
    val cohort: TimeSeriesCohort,
    val schema: Schema,
    hadoopConfiguration: HConfiguration,
    outFile: File
//    fileSystem: FileSystem,
//    destinationPath: Path
  ) {
    val datumWriter: GenericDatumWriter[GenericRecord] = new GenericDatumWriter[GenericRecord]( schema )

    def writer: \/[Throwable, Writer] = {
      \/ fromTryCatchNonFatal {
        val writer = new DataFileWriter[GenericRecord]( datumWriter ).setCodec( CodecFactory.snappyCodec )
        if ( !destinationExists ) writer.create( schema, outFile )
        else writer.appendTo( outFile )
      }
    }

    private def destinationExists: Boolean = {
      outFile.exists
//      fileSystem exists destinationPath
    }
//    private def input: SeekableInput = new FsInput( destinationPath, hadoopConfiguration )
//    private def out: \/[Throwable, OutputStream] = {
//      writer map { w => w.
//        if ( destinationExists ) {
//          w appendTo outFile
//          //        trace( s"append: fileSystem: [${fileSystem}]" )
//          //        trace( s"append: destinationPath: [${destinationPath}]" )
//          //        trace( s"""append: append supported: [${fileSystem.getConf.get("dfs.support.append")}]""")
//          //        fileSystem append destinationPath
//        } else {
//          w.create( schema, outFile )
//          //        trace( s"create: fileSystem: [${fileSystem}]" )
//          //        trace( s"create: destinationPath: [${destinationPath}]" )
//          //        trace( s"""create: append supported: [${fileSystem.getConf.get("dfs.support.append")}]""")
//          //        fileSystem create destinationPath
//        }
//      }
//    }
  }

  trait WritersContextProvider {
    def config: Config
    def hadoopConfiguration: HConfiguration
    def timeseriesSchema: Schema
    def datapointSubSchema( tsSchema: Schema ): Schema
//    def fileSystem: FileSystem = FileSystem get hadoopConfiguration
    def outFile: File
//    def outPath: Path
    def context( cohort: TimeSeriesCohort ): WritersContext = {
      new WritersContext(
        cohort,
        schema = timeseriesSchema,
        hadoopConfiguration = hadoopConfiguration,
        outFile = outFile
//        fileSystem = fileSystem,
//        destinationPath = outPath
      )
    }
  }

  trait LocalhostWritersContextProvider extends WritersContextProvider {
    override def hadoopConfiguration: HConfiguration = {
      val conf = new HConfiguration
      conf.set( "fs.defaultFS", "hdfs://localhost" )
      conf.setInt( "dfs.replication", 1 )
      conf
    }

    override lazy val timeseriesSchema: Schema = {
      val avsc = getClass.getClassLoader.getResourceAsStream( "avro/timeseries.avsc" )
      new Schema.Parser().parse( avsc )
    }

    override def datapointSubSchema( tsSchema: Schema ): Schema = tsSchema.getField( "points" ).schema.getElementType

    val trainingHome: String = {
      if ( config hasPath "spotlight.training.home" ) config getString "spotlight.training.home"
      else "."
    }

    import better.files.{ File => BFile }
    BFile( trainingHome ).createIfNotExists( asDirectory = true )

    override def outFile: File = {
      import org.joda.{ time => joda }
      val formatter = joda.format.DateTimeFormat forPattern "yyyyMMddHH"
      val suffix = formatter print joda.DateTime.now
      BFile(  s"${trainingHome}/timeseries-${suffix}.avro" ).toJava
    }

//    override def outPath: Path = new Path( outFile.getCanonicalPath )
  }


  type Writer = DataFileWriter[GenericRecord]

  case class RecordsContext( records: List[GenericRecord], writer: Writer )


  trait AvroWriter {
    type Op[I, O] = Kleisli[Task, I, O]

    def write: Op[TimeSeriesCohort, Unit] = makeWritersContext >==> makeRecordsContext >==> writeRecords >==> closeWriter

    def makeWritersContext: Op[TimeSeriesCohort, WritersContext]

    def makeRecordsContext: Op[WritersContext, RecordsContext] = {
      for {
        writer <- makeWriter
        records <- genericRecords
      } yield RecordsContext( records, writer )
    }

    def genericRecords: Op[WritersContext, List[GenericRecord]]

    def makeWriter: Op[WritersContext, Writer]

    def writeRecords: Op[RecordsContext, Writer]

    def closeWriter: Op[Writer, Unit]

    val noop: Op[TimeSeriesCohort, Unit] = Kleisli[Task, TimeSeriesCohort, Unit] { cohort => Task now { () } }
  }
}

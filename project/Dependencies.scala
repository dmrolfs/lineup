import sbt.Keys._
import sbt._

object Dependencies {
  object peds {
    val version = "0.4.7-SNAPSHOT"
    def module( id: String ) = "com.github.dmrolfs" %% s"peds-$id" % version withSources() withJavadoc()
    def all = Seq( commons, akka, archetype )

    val commons = module( "commons" )
    val archetype = module( "archetype" )
    val akka = module( "akka" )
    val builder = "com.github.dmrolfs" %% "shapeless-builder" % "1.0.0"
  }

  object demesne {
    val version = "2.1.3-SNAPSHOT"
    def module( id: String ) = "com.github.dmrolfs" %% s"demesne-$id" % version withSources() withJavadoc()
    val core = module( "core" )
    val testkit = module( "testkit" )
  }

  object akka {
    val version = "2.4.16"
    def module( id: String ) = "com.typesafe.akka" %% s"akka-$id" % version withSources() withJavadoc()
    val all: Seq[ModuleID] = Seq( actor, stream, agent, cluster, clusterSharding, contrib, persistence, remote, slf4j )

    val actor = module( "actor" )
    val stream = module( "stream" )
    val agent = module( "agent" )
    val cluster = module( "cluster" )
    val clusterSharding = module( "cluster-sharding" )
    val contrib = module( "contrib" )
    val persistence = module( "persistence" )
    val persistenceQuery = module( "persistence-query-experimental" )
    val remote = module( "remote" )
    val slf4j = module( "slf4j" )
    val testkit = module( "testkit" )
    val streamsTestkit = module( "stream-testkit" )

    val kryo = "com.github.romix.akka" %% "akka-kryo-serialization" % "0.5.0"
    val kryoSerializers = "de.javakaffee" % "kryo-serializers" % "0.41"
  }

  object persistence {
    val cassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.22"
    val leveldb = "org.iq80.leveldb" % "leveldb" % "0.7" // "org.iq80.leveldb" % "leveldb" % "0.9"
    val leveldbjni = "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8" // "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
  }

  object scalaz {
    val version = "7.2.8"
    def module( id: String ) = "org.scalaz" %% s"scalaz-$id" % version withSources() withJavadoc()

    val core = module( "core" )
    val concurrent = module( "concurrent" )
  }

  object time {
    val joda = "joda-time" % "joda-time" % "2.9.6"
    val jodaConvert = "org.joda" % "joda-convert" % "1.8.1"
    val scalaTime = "com.github.nscala-time" %% "nscala-time" % "2.14.0"
    def all = Seq( joda, jodaConvert, scalaTime )
  }

  object log {
    val persistLogging = "com.persist" %% "persist-logging" % "1.2.6"
    val typesafe = "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0"

    object logback {
      val version = "1.1.7"
      def module( id: String ) = "ch.qos.logback" % s"logback-$id" % version

      val core = module( "core" )
      val classic = module( "classic" )
    }

    val slf4j = "org.slf4j" % "slf4j-api" % "1.7.21" intransitive
    val log4jOverSlf4j = "org.slf4j" % "log4j-over-slf4j" % "1.7.21"

    def all = Seq( typesafe, logback.core, logback.classic, slf4j, log4jOverSlf4j, persistLogging )
  }

  object metrics {
    val version = "3.1.2"
    def module( id: String ) = "io.dropwizard.metrics" % s"metrics-$id" % "3.1.2"
    def all = Seq( sigar, core, graphite, metricsScala, hdrhistogramReservoir, hdrhistogram ) ++ kamon.all

    val sigar = "org.hyperic" % "sigar" % "1.6.4"

    val core = module( "core" )
    val graphite = module( "graphite" )
    val metricsScala = "nl.grons" %% "metrics-scala" % "3.5.5_a2.3"
    val hdrhistogramReservoir = "org.mpierce.metrics.reservoir" % "hdrhistogram-metrics-reservoir" % "1.1.2"
    val hdrhistogram = "org.hdrhistogram" % "HdrHistogram" % "2.1.9"

    object kamon {
      val version = "0.6.2"
      def module( id: String ) = "io.kamon" %% s"kamon-$id" % version
      def all = Seq( core, scala, akka, akkaRemote, system, statsd )

      val core = module( "core" )
      val scala = module( "scala" )
      val akka = module( "akka" )
      val akkaRemote = module( "akka-remote" )
      val system = module( "system-metrics" )
      val statsd = module( "statsd" )
      val logReporter = module( "log-reporter" )
    }
  }

  object facility {
    val offheap = "sh.den" % "scala-offheap_2.11" % "0.1"
    val fastutil = "it.unimi.dsi" % "fastutil" % "7.0.13" withSources() withJavadoc()
    val bloomFilter = "com.github.alexandrnikitin" %% "bloom-filter" % "0.7.0" withSources() withJavadoc()
    val json4sJackson = "org.json4s" %% "json4s-jackson" % "3.5.0"
    val uuid = "com.eaio.uuid" % "uuid" % "3.4"
    val config = "com.typesafe" % "config" % "1.3.1"
//    val pureConfig = "com.github.melrief" %% "pureconfig" % "0.1.5"
    val shapeless = "com.chuusai" %% "shapeless" % "2.3.2" withSources() withJavadoc()
    val parboiled = "org.parboiled" %% "parboiled" % "2.1.3"
    val inflector = "org.atteo" % "evo-inflector" % "1.2.1"
    val squants = "org.typelevel"  %% "squants"  % "1.0.0"
    val accord = "com.wix" %% "accord-core" % "0.6"
    val lang = "org.apache.commons" % "commons-lang3" % "3.5"
    val math3 = "org.apache.commons" % "commons-math3" % "3.6.1" withSources() withJavadoc()
//    val suanshu = "com.numericalmethod" % "suanshu" % "3.4.0" intransitive()  // don't want to use due to $$$
    val scopt = "com.github.scopt" %% "scopt" % "3.5.0"
    val pyrolite = "net.razorvine" % "pyrolite" % "4.10"
    val msgpack = "org.velvia" % "msgpack4s_2.11" % "0.5.2"

    val hadoopClient = "org.apache.hadoop" % "hadoop-client" % "2.7.1" intransitive // exclude( "log4j", "log4j" )

    object avro {
      val version = "1.8.1"
      def all = Seq( core, scavro )
      val core = "org.apache.avro" % "avro" % version
      val tools = "org.apache.avro" % "avro-tools" % version
      val mapred = "org.apache.avro" % "avro-mapred" % version
      val scavro = "org.oedura" %% "scavro" % "1.0.1"
    }

    object betterFiles {
      val version = "2.16.0"
      val core = "com.github.pathikrit" %% "better-files" % version
      val akka = "com.github.pathikrit" %% "better-files-akka" % version
      def all = Seq( core, akka )
    }
  }

  object quality {
    val scalatest = "org.scalatest" %% "scalatest" % "3.0.0" withSources() withJavadoc()
    val scalazMatchers = "org.typelevel" %% "scalaz-scalatest" % "1.1.0" withSources() withJavadoc()

    val inmemory = "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.3.18"

    object mockito {
      val version = "1.10.19"
      def module( id: String ) = "org.mockito" % s"mockito-$id" % version withSources() withJavadoc()
      val core = module( "core" )
    }
  }


  val commonDependencies = {
    log.all ++
    peds.all ++
    time.all ++
    Seq(
      akka.actor,
      akka.stream,
      akka.slf4j,
      akka.kryo,
      akka.kryoSerializers,
      akka.persistence,
      akka.persistenceQuery,
      persistence.cassandra,
persistence.leveldb,
persistence.leveldbjni,
      log.logback.classic,
      facility.bloomFilter,
      facility.uuid,
      facility.config,
      facility.shapeless,
      scalaz.core
    ) ++
    Scope.test(
      akka.testkit,
      quality.scalatest,
      quality.scalazMatchers,
      quality.mockito.core
    )
  }

  val defaultDependencyOverrides = Set(
    scalaz.core //,
//    akka.actor,
//    time.joda
  )


  object Scope {
    def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
    def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
    def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
    def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
    def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )
  }
}

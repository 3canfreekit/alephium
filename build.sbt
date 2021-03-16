import sbt._
import sbt.Keys._
import Dependencies._

Global / cancelable := true // Allow cancellation of forked task without killing SBT

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"

def baseProject(id: String): Project = {
  Project(id, file(id))
    .settings(commonSettings: _*)
    .settings(name := s"alephium-$id")
}

val scalastyleCfgFile     = "project/scalastyle-config.xml"
val scalastyleTestCfgFile = "project/scalastyle-test-config.xml"

lazy val root: Project = Project("alephium-scala-blockflow", file("."))
  .settings(commonSettings: _*)
  .settings(
    name := "alephium",
    unmanagedSourceDirectories := Seq(),
    scalastyle := {},
    scalastyle in Test := {},
    publish / skip := true
  )
  .aggregate(macros, util, serde, io, crypto, api, rpc, app, benchmark, flow, protocol, wallet)

def mainProject(id: String): Project =
  project(id).enablePlugins(JavaAppPackaging).dependsOn(flow)

def project(path: String): Project = {
  baseProject(path)
    .configs(IntegrationTest extend Test)
    .settings(
      Defaults.itSettings,
      inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings),
      Compile / scalastyleConfig := root.base / scalastyleCfgFile,
      Test / scalastyleConfig := root.base / scalastyleTestCfgFile,
      inConfig(IntegrationTest)(ScalastylePlugin.rawScalastyleSettings()),
      IntegrationTest / scalastyleConfig := root.base / scalastyleTestCfgFile,
      IntegrationTest / scalastyleTarget := target.value / "scalastyle-it-results.xml",
      IntegrationTest / scalastyleSources := (IntegrationTest / unmanagedSourceDirectories).value
    )
}

lazy val macros = project("macros")
  .settings(
    libraryDependencies += `scala-reflect`(scalaVersion.value),
    wartremoverErrors in (Compile, compile) := Warts.allBut(
      wartsCompileExcludes :+ Wart.AsInstanceOf: _*)
  )

lazy val util = project("util")
  .dependsOn(macros)
  .settings(
    scalacOptions -= "-Xlint:nonlocal-return",
    libraryDependencies ++= Seq(
      akka,
      `akka-slf4j`,
      bcprov,
      `scala-reflect`(scalaVersion.value)
    )
  )

lazy val serde = project("serde")
  .settings(
    Compile / sourceGenerators += (sourceManaged in Compile).map(Boilerplate.genSrc).taskValue,
    Test / sourceGenerators += (sourceManaged in Test).map(Boilerplate.genTest).taskValue,
  )
  .dependsOn(util % "test->test;compile->compile")

lazy val crypto = project("crypto")
  .dependsOn(util % "test->test;compile->compile", serde)
  .settings(
    libraryDependencies += `blake3-jni`
  )

lazy val io = project("io")
  .dependsOn(util % "test->test;compile->compile", serde, crypto)
  .settings(
    libraryDependencies += rocksdb
  )

lazy val rpc = project("rpc")
  .settings(
    libraryDependencies ++= Seq(
      `akka-http`,
      `akka-http-circe`,
      `akka-stream`,
      `circe-parser`,
      `circe-generic`,
      `scala-logging`,
      `akka-test`,
      `akka-http-test`
    ),
    publish / skip := true
  )
  .dependsOn(util % "test->test;compile->compile")

lazy val api = project("api")
  .dependsOn(protocol, crypto, serde, util % "test->test;compile->compile")
  .settings(
    libraryDependencies ++= Seq(
      `circe-core`,
      `circe-generic`,
      `scala-logging`,
      `tapir-core`,
      `tapir-circe`,
    )
  )

lazy val app = mainProject("app")
  .dependsOn(api, rpc, util % "it,test->test", flow, flow % "it,test->test", wallet)
  .settings(
    mainClass in assembly := Some("org.alephium.app.Boot"),
    assemblyJarName in assembly := s"alephium-${version.value}.jar",
    test in assembly := {},
    assemblyMergeStrategy in assembly := {
      case "logback.xml" => MergeStrategy.first
      case PathList("META-INF", "maven", "org.webjars", "swagger-ui", xs @ _*) =>
        MergeStrategy.first
      case other => (assemblyMergeStrategy in assembly).value(other)
    },
    libraryDependencies ++= Seq(
      `akka-http-cors`,
      `akka-http-test`,
      `akka-stream-test`,
      `tapir-core`,
      `tapir-circe`,
      `tapir-akka`,
      `tapir-openapi`,
      `tapir-openapi-circe`,
      `tapir-swagger-ui`
    ),
    publish / skip := true
  )

lazy val benchmark = project("benchmark")
  .enablePlugins(JmhPlugin)
  .dependsOn(flow)
  .settings(
    publish / skip := true,
    scalacOptions += "-Xdisable-assertions"
  )

lazy val flow = project("flow")
  .dependsOn(crypto, io, serde, util % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      akka,
      `akka-slf4j`,
      logback,
      `scala-logging`,
      weupnp
    ),
    publish / skip := true
  )
  .dependsOn(protocol % "test->test;compile->compile")

lazy val protocol = project("protocol")
  .dependsOn(crypto, io % "compile->compile;test->test", serde, util % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      fastparse,
      pureconfig
    )
  )

lazy val wallet = project("wallet")
  .dependsOn(api, crypto, util % "test->test", protocol % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      `akka-http`,
      `akka-http-circe`,
      `akka-http-test`,
      `scala-logging`,
      `circe-core`,
      `circe-generic`,
      `tapir-core`,
      `tapir-circe`,
      `tapir-akka`,
      `tapir-openapi`,
      `tapir-openapi-circe`,
      `tapir-swagger-ui`,
      `tapir-client`,
      `sttp-akka-http-backend`,
      `scala-logging`,
      logback
    ),
    publish / skip := true
  )

val publishSettings = Seq(
  organization := "org.alephium",
  homepage := Some(url("https://github.com/alephium/alephium")),
  licenses := Seq("LGPL 3.0" -> new URL("https://www.gnu.org/licenses/lgpl-3.0.en.html")),
  developers := List(
    Developer(
      id    = "alephium core dev",
      name  = "alephium core dev",
      email = "dev@alephium.org",
      url   = url("https://alephium.org/")
    )
  )
)

val commonSettings = publishSettings ++ Seq(
  scalaVersion := "2.13.5",
  parallelExecution in Test := false,
  scalacOptions ++= Seq(
//    "-Xdisable-assertions", // TODO: use this properly
    "-deprecation",
    "-encoding",
    "utf-8",
    "-explaintypes",
    "-feature",
    "-unchecked",
    "-Xsource:3",
    "-Xfatal-warnings",
    "-Xlint:adapted-args",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:doc-detached",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:missing-interpolator",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:type-parameter-shadow",
    "-Xlint:nonlocal-return",
    "-Ywarn-dead-code",
    "-Ywarn-extra-implicit",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:imports",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:params",
    "-Ywarn-unused:patvars",
    "-Ywarn-unused:privates",
    "-Ywarn-value-discard"
  ),
  scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
  wartremoverErrors in (Compile, compile) := Warts.allBut(wartsCompileExcludes: _*),
  wartremoverErrors in (Test, test) := Warts.allBut(wartsTestExcludes: _*),
  wartremoverErrors in (IntegrationTest, test) := Warts.allBut(wartsTestExcludes: _*),
  fork := true,
  Test / scalacOptions ++= Seq("-Xcheckinit", "-Wconf:cat=other-non-cooperative-equals:s"),
  Test / javaOptions += "-Xss2m",
  Test / envVars += "ALEPHIUM_ENV"            -> "test",
  IntegrationTest / envVars += "ALEPHIUM_ENV" -> "it",
  libraryDependencies ++= Seq(
    `akka-test`,
    scalacheck,
    scalatest,
    scalatestplus
  )
)

val wartsCompileExcludes = Seq(
  Wart.MutableDataStructures,
  Wart.Var,
  Wart.Overloading,
  Wart.ImplicitParameter,
  Wart.NonUnitStatements,
  Wart.Nothing,
  Wart.Null, // Partially covered by scalastyle, only use _ inside actors
  Wart.Return, // Covered by scalastyle
  Wart.Any,
  Wart.Throw,
  Wart.Equals,
  Wart.StringPlusAny,
  Wart.While
)

val wartsTestExcludes = wartsCompileExcludes ++ Seq(
  Wart.PublicInference,
  Wart.TraversableOps,
  Wart.OptionPartial
)

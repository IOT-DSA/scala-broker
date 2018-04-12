import sbt._
import sbt.Keys._

object Testing {

  lazy val itTest = TaskKey[Unit]("it-tests")
  lazy val allTest = TaskKey[Unit]("all-tests")
  lazy val itTestOnly = inputKey[Unit]("it-test-only")


  private lazy val itSettings =
    inConfig(Test)(Defaults.testSettings) ++
      Seq(
        fork in Test := false,
        parallelExecution in Test := false)

  def settings(scope:Configuration) = itSettings ++ Seq(

    itTest := {
      val docker = (publishLocal).value
      val itTests = (test in Test).value
    },

    allTest := {
      val unitTests = (test in Test).value
      val itTestResult = itTest.value
    },

    itTestOnly := {
      val docker = (publishLocal).value
      val itTests = (testOnly in Test).evaluated
    }

  )
}
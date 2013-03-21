package cc.factorie


import optimize.{BatchTrainer, LikelihoodExample}
import org.scalatest.junit.{JUnitSuite, AssertionsForJUnit}
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import org.junit.Assert._
import org.junit.Test
import org.junit.Before

import cc.factorie._


/**
Learning package tests needs to verify:
SampleRank requires
-transition function over states [MH/Gibbs/Local/SettingsSampler]
-training signal [problem specific]
-update rule [MIRA,CW,PA etc]


(1) Show how to integrate various update rules with SampleRank/CD
[Learning Method] X [Update Rule] X [Parameter Averaging] X [Sampler]
-SampleRank          -GA            -YES                  -MH
-CD                  -MIRA          -NO                   -Settings
-Perceptron          -CW
-RL                  -ARROW
-2nd order  (2) Show examples of how to specify signals/objectives
-Penalty/Reward
-F1/Accuracy

(3) Show examples of specifying proposal distribution


DOES MIRA/CW work with manually specified weights?

 ** TEST SAMPLERANK**
test if model ranking agrees with training signal (randomly created?)


@Authors Michael Wick and Sameer Singh
@Since 0.9

 */

class RealVariableTest extends JUnitSuite {
  @Test def testRealVariableWorks() {
    class Prob(val scoreVal:Double) extends RealVariable(scoreVal)
    class Data(val scoreVal: Double, val truth: Boolean) extends LabeledBooleanVariable(truth) {
      val score=new Prob(scoreVal)
    }
    val trainings=new ArrayBuffer[Data]
    trainings+=new Data(0.1, false)
    trainings+=new Data(0.4, false)
    trainings+=new Data(0.6, true)
    trainings+=new Data(0.8, true)
    object simpleTemplate extends DotTemplateWithStatistics2[Data, Prob]{
      lazy val weights = new la.DenseTensor2(BooleanDomain.dimensionSize, RealDomain.dimensionSize)
      def unroll1(data: Data) = Factor(data, data.score)
      def unroll2(prob: Prob) = Nil
    }
    val model = new CombinedModel(simpleTemplate)
    val objective = new HammingTemplate[Data]

    val pieces = new ArrayBuffer[LikelihoodExample[Data]]
    pieces+=new LikelihoodExample(trainings, InferByBPLoopy)
    val trainer = new BatchTrainer(model)
    while (!trainer.optimizer.isConverged) {
      trainer.processExamples(pieces)
      println("Accuracy after sampling: " + objective.accuracy(trainings))
    }
  }
}


//extends JUnitSuite with TestUtils{
class SampleRankTest extends AssertionsForJUnit {
  val numVariables = 4
  val numProposals = 1000

  class MyBool extends BooleanVariable(false)
  {
    var next: MyBool = null
    var prev: MyBool = null
  }
  //
  //create variables with a ring graph structure
  var bools: Seq[MyBool] = null

  val trainingSignal = new CombinedModel (
    //
    //this template unrolls a "ring" structured graphical model
    new TupleTemplateWithStatistics2[MyBool, MyBool] {
      def unroll1(b: MyBool) = Factor(b, b.next)
      def unroll2(b: MyBool) = Factor(b.prev, b)
      def score(v1:MyBool#Value, v2:MyBool#Value): Double = {
        //var v1 = s._1
        //var v2 = s._2
        if (v1 == v2) -1.0
        else 1.0
      }
    }
  )
  var model: CombinedModel = null


  class AllPairsProposer(model: CombinedModel) extends MHSampler[Null](model)
  {
    def propose(context: Null)(implicit delta: DiffList): Double =
      {
        for (b <- bools) b.set(random.nextBoolean)(delta)
        0.0
      }
  }

//
//  abstract class AllPairsCD1Proposer(model: CombinedModel) extends ContrastiveDivergence[Null](model)
//  {
//    def propose(context: Null)(implicit delta: DiffList): Double =
//      {
//        for (b <- bools) b.set(random.nextBoolean)(delta)
//        0.0
//      }
//  }

  @Before def initialize() =
    {
      println("TESTING LEARNING FRAMEWORK")
      bools = (for (i <- 0 until numVariables) yield new MyBool).toSeq
      for (i <- 0 until bools.length - 1)
        {
          bools(i).next = bools(i + 1)
          bools(i + 1).prev = bools(i)
        }
      bools(0).prev = bools(bools.length - 1)
      bools(bools.length - 1).next = bools(0)
      println("NUM BOOL VARS: " + bools.size)

      model = new CombinedModel(
        new DotTemplateWithStatistics2[MyBool, MyBool]
        {
          //def statisticsDomains = ((BooleanDomain, BooleanDomain))
          lazy val weights = new la.DenseTensor2(BooleanDomain.size, BooleanDomain.size)
          def unroll1(b: MyBool) = Factor(b, b.next)
          def unroll2(b: MyBool) = Factor(b.prev, b)
        }
      )
    }


  def decodeConfiguration(v: Int, configuration: Seq[MyBool]): Unit =
    {
      val result = new ArrayBuffer[Boolean]
      var tmpV = v
      while (tmpV != 0)
        {
          result += (tmpV % 2 == 1)
          tmpV /= 2
        }
      //pad
      for (i <- 0 until configuration.length - result.length)
        result += false

      for (i <- 0 until configuration.length)
        configuration(i).set(result(i))(null)
    }

  def checkAllPairs =
    {
      //
      //Test extremes
      var fpErrors = 0
      var fnErrors = 0
      for (i <- 0 until math.pow(2, bools.length).toInt)
        {
          decodeConfiguration(i, bools)
          val modelScoreI = model.currentScore(bools)
          val truthScoreI = trainingSignal.currentScore(bools)

          for (j <- i + 1 until math.pow(2, bools.length).toInt)
            {
              decodeConfiguration(j, bools)
              val modelScoreJ = model.currentScore(bools)
              val truthScoreJ = trainingSignal.currentScore(bools)

              if (truthScoreI > truthScoreJ)
                if (modelScoreI <= modelScoreJ)
                  fpErrors += 1
              //assert(modelScoreI>modelScoreJ)
              if (truthScoreI < truthScoreJ)
                if (modelScoreI >= modelScoreJ)
                  fnErrors += 1
              //assert(modelScoreI<modelScoreJ)
            }
        }
      println("NUMBER OF ERRORS(FP,FN) = (" + fpErrors + "," + fnErrors + ")")
      assert(fpErrors + fnErrors == 0)
    }

//  @Test def verifySampleRankGA =
//    {
//      val sampler = new AllPairsProposer(model) {
//        override def objective = trainingSignal
//        temperature = 1000.0
//      }
//      val trainer = new SampleRank(sampler, new cc.factorie.optimize.StepwiseGradientAscent)
//      trainer.process(null, numProposals)
//      checkAllPairs
//    }
//
//  @Test def verifySampleRankParameterAveraging =
//    {
//      val trainer = new AllPairsProposer(model) with SampleRank
//              with GradientAscentUpdates with ParameterAveraging
//      {
//        override def objective = trainingSignal
//        temperature = 1000.0
//      }
//      trainer.process(numProposals)
//      trainer.setWeightsToAverage
//      // checkAllPairs
//    }
//
//
//  @Test def verifySampleRankMIRA =
//    {
//      val trainer = new AllPairsProposer(model) with SampleRank
//              with MIRAUpdates
//      {
//        override def objective = trainingSignal
//        temperature = 1000.0
//      }
//      trainer.process(numProposals)
//      checkAllPairs
//    }
//
//  @Test def verifySampleRankCW =
//    {
//      val trainer = new AllPairsProposer(model) with SampleRank
//              with ConfidenceWeightedUpdates
//      {
//        override def objective = trainingSignal
//        temperature = 1000.0
//      }
//      trainer.process(numProposals)
//      checkAllPairs
//    }
//
//  @Test def verifySampleRankAROW =
//    {
//      val trainer = new AllPairsProposer(model) with SampleRank
//              with AROWUpdates
//      {
//        override def objective = trainingSignal
//        temperature = 1000.0
//      }
//      trainer.process(numProposals)
//      checkAllPairs
//    }
  /*

def assignMAP(bools:Seq[MyBool], model:Model) : Int =
  {
    var maxScore = Math.NEG_INF_DOUBLE
    var maxConfig:Int = 0
    for(i<-0 until Math.pow(2,bools.length).toInt)
{
  decodeConfiguration(i,bools)
  val modelScore = model.scoreAll(bools)
  if (modelScore > maxScore) {
    maxScore = modelScore
    maxConfig = i
  }
}
    decodeConfiguration(maxConfig, bools)
    maxConfig
  }

def checkAllAgainstTruth =
  {
    assignMAP(bools,trainingSignal)
    val mapScore = model.scoreAll(bools)
    var errors = 0
    for(i<-0 until Math.pow(2,bools.length).toInt)
{
  decodeConfiguration(i,bools)
  val configScore = model.scoreAll(bools)
  if(configScore>mapScore)
    errors +=1
}
    println("NUM CD ERRORS: " + errors)
    assert(errors==0)
  }

@Test def verifyCD1 =
  {
    val trainer = new AllPairsCD1Proposer(model) with GradientAscentUpdates
    val mapConfig = assignMAP(bools,trainingSignal)
    for(i<-0 until numProposals)
{
  //
  //initialize to MAP
  decodeConfiguration(mapConfig,bools)
  trainer.process(1)
}
    checkAllAgainstTruth
  }*/
}

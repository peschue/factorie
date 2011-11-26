/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.app.nlp.pos

import cc.factorie._
import cc.factorie.app.nlp._
//import cc.factorie.VariableSettingsGreedyMaximizer

class POS1 {
  def this(savedModelDir:String) = { this(); PosModel.load(savedModelDir)}
  
  object PosFeaturesDomain extends CategoricalVectorDomain[String]
  class PosFeatures(val token:Token) extends BinaryFeatureVectorVariable[String] {
    def domain = PosFeaturesDomain
    override def skipNonCategories = true
  }

  def useSentenceBoundaries = false
  object PosModel extends TemplateModel(
    // Bias term on each individual label 
    new TemplateWithDotStatistics1[PosLabel],
    // Factor between label and observed token
    new TemplateWithDotStatistics2[PosLabel,PosFeatures] {
      def unroll1(label: PosLabel) = Factor(label, label.token.attr[PosFeatures])
      def unroll2(tf: PosFeatures) = Factor(tf.token.attr[PosLabel], tf)
    },
    // Transition factors between two successive labels
    new TemplateWithDotStatistics2[PosLabel, PosLabel] {
      def unroll1(label: PosLabel) = 
        if (useSentenceBoundaries) {
          if (label.token.sentenceHasPrev) Factor(label.token.sentencePrev.attr[PosLabel], label) else Nil
        } else {
          if (label.token.hasPrev) Factor(label.token.prev.attr[PosLabel], label) else Nil
        } 
      def unroll2(label: PosLabel) = 
        if (useSentenceBoundaries) {
          if (label.token.sentenceHasNext) Factor(label, label.token.sentenceNext.attr[PosLabel]) else Nil
        } else {
          if (label.token.hasNext) Factor(label, label.token.next.attr[PosLabel]) else Nil
        }
    }
  )
  
  def initPosFeatures(document:Document): Unit = {
    for (token <- document) {
      val rawWord = token.string
      val word = cc.factorie.app.strings.simplifyDigits(rawWord)
      val features = token.attr += new PosFeatures(token)
      features += "W="+word.toLowerCase
      features += "SHAPE="+cc.factorie.app.strings.stringShape(rawWord, 2)
      features += "SUFFIX3="+word.takeRight(3)
      features += "PREFIX3="+word.take(3)
      //if (token.isCapitalized) features += "CAPITALIZED"
      //if (token.containsDigit) features += "NUMERIC"
      if (token.isPunctuation) features += "PUNCTUATION"
    }
    for (sentence <- document.sentences)
      cc.factorie.app.chain.Observations.addNeighboringFeatureConjunctions(sentence, (t:Token)=>t.attr[PosFeatures], List(1), List(-1))
  }

  // TODO Change this to use Viterbi! -akm
  def process(document:Document): Unit = {
    for (token <- document) if (token.attr[PosLabel] == null) token.attr += new PosLabel(token, PosDomain.getCategory(0)) // init value doens't matter
    val localModel = new TemplateModel(PosModel.templates(0), PosModel.templates(1))
    val localPredictor = new VariableSettingsGreedyMaximizer[PosLabel](localModel)
    for (label <- document.tokens.map(_.attr[PosLabel])) localPredictor.process(label)
    val predictor = new VariableSettingsSampler[PosLabel](PosModel)
    for (i <- 0 until 3; label <- document.tokens.map(_.attr[PosLabel])) predictor.process(label)
  }
  
  // Add run as server
}




// For example:
// POS1 --train /Users/mccallum/research/data/ie/ner2003/eng.train --test /Users/mccallum/research/data/ie/ner2003/eng.testa --model pos.fac
// POS1 --model pos.fac --run ~/research/data/text/nipstxt/nips11/0620.txt
object POS1 extends POS1 {
  def main(args: Array[String]): Unit = {
    object opts extends cc.factorie.util.DefaultCmdOptions {
      val trainFile =    new CmdOption("train", "eng.train", "FILE", "CoNLL 2003 format file from which to get training data.") { override def required = true }
      val testFile =     new CmdOption("test", "eng.testa", "FILE", "CoNLL 2003 format file from which to get testing data.") { override def required = true }
      val modelDir =     new CmdOption("model", "pos.fac", "DIR", "Directory in which to save the trained model.")
      val runFiles =     new CmdOption("run", List("input.txt"), "FILE...", "Plain text files from which to get data on which to run.")
    }
    opts.parse(args)
    if (opts.trainFile.wasInvoked)
      train()
    else if (opts.runFiles.wasInvoked)
      run()
    else
      throw new Error("Must use either --train or --run.")
        
    def train(): Unit = {
      // Read in the data
      val trainDocuments = LoadConll2003.fromFilename(opts.trainFile.value)
      val testDocuments = LoadConll2003.fromFilename(opts.testFile.value)
      //(trainDocuments ++ testDocuments).foreach(_.tokens.foreach(_.attr.remove[cc.factorie.app.nlp.ner.NerLabel]))

      // Add features for NER
      trainDocuments.foreach(initPosFeatures(_))
      testDocuments.foreach(initPosFeatures(_))
      println("Example Token features")
      println(trainDocuments(3).tokens.take(10).map(_.attr[PosFeatures].toString).mkString("\n"))
      println("Num TokenFeatures = "+PosFeaturesDomain.dimensionDomain.size)
    
      // Get the variables to be inferred (for now, just operate on a subset)
      val trainLabels = trainDocuments.flatten.map(_.attr[PosLabel]) //.take(10000)
      val testLabels = testDocuments.flatten.map(_.attr[PosLabel]) //.take(2000)
    
      def printEvaluation(iteration:String): Unit = {
        println("Iteration "+iteration)
        println("Train Token accuracy = "+ PosObjective.aveScore(trainLabels))
        println(" Test Token accuracy = "+ PosObjective.aveScore(testLabels))
        /*for (docs <- List(trainDocuments, testDocuments)) {
        if (docs.length > 300) println ("TRAIN") else println("TEST") // Fragile
        val tokenEvaluation = new LabelEvaluation(PosDomain)
        for (doc <- docs; token <- doc) tokenEvaluation += token.attr[PosLabel]
        println(tokenEvaluation)
        }*/
      }

      // Train for 5 iterations
      (trainLabels ++ testLabels).foreach(_.setRandomly())
      val learner = new VariableSettingsSampler[PosLabel](PosModel, ZeroOneLossObjective) with SampleRank with GradientAscentUpdates
      val predictor = new VariableSettingsSampler[PosLabel](PosModel)
      for (i <- 1 until 10) {
        learner.processAll(trainLabels)
        predictor.processAll(testLabels)
        printEvaluation(i.toString)
      }

      // Predict, also by sampling, visiting each variable 3 times.
      //predictor.processAll(testLabels, 3)
      for (i <- 0 until 3; label <- testLabels) predictor.process(label)
    
      // Evaluate
      printEvaluation("FINAL")
    
      if (opts.modelDir.wasInvoked)
        PosModel.save(opts.modelDir.value)
    }
    
    
    def run(): Unit = {
      PosModel.load(opts.modelDir.value)
      for (filename <- opts.runFiles.value) {
        val document = LoadPlainText.fromFile(new java.io.File(filename), segmentSentences=false)
        initPosFeatures(document)
        process(document)
        for (token <- document)
          println("%s %s".format(token.string, token.attr[PosLabel].categoryValue))
      }
    }
  }

  
}
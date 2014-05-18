/* Copyright (C) 2008-2014 University of Massachusetts Amherst.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://github.com/factorie
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */
package cc.factorie.app.nlp.hcoref

import cc.factorie.infer.SettingsSampler
import scala.util.Random
import cc.factorie.util.Hooks1
import scala.reflect.ClassTag

/**
 * User:harshal, John Sullivan
 * Date: 10/28/13
 */
abstract class CorefSampler[Vars <: NodeVariables[Vars]](override val model:CorefModel[Vars], val mentions:Iterable[Node[Vars]], val iterations:Int)(implicit override val random:Random, val varsTag:ClassTag[Vars])
  extends SettingsSampler[(Node[Vars], Node[Vars])](model) {
  this: PairContextGenerator[Vars] with MoveGenerator[Vars] =>

  this.temperature = 0.001

  val beforeInferHooks = new Hooks1[Unit]
  protected def beforeInferHook = beforeInferHooks()

  def infer {
    beforeInferHook
    processAll(contexts)
  }

}

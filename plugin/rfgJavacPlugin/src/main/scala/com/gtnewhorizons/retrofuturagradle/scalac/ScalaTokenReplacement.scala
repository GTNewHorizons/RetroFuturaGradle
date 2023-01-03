package com.gtnewhorizons.retrofuturagradle.scalac

import scala.reflect.api.Names
import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import com.gtnewhorizons.retrofuturagradle.TokenReplacement

import java.util.function.IntConsumer
import scala.tools.nsc.ast.TreeDSL
import scala.tools.nsc.transform.TypingTransformers

class ScalaTokenReplacement(val global: Global) extends Plugin {

  import global._

  val name = "RetrofuturagradleScalaTokenReplacement"
  val description = "Replaces tokens in matching source inputs"
  val components: List[PluginComponent] = List[PluginComponent](Component)
  val replacer = new TokenReplacement()


  override def init(options: List[String], error: String => Unit): Boolean = {
    if (options.length != 1) {
      error("Missing option")
    } else {
      replacer.loadConfig(options.head)
    }
    true
  }

  override val optionsHelp: Option[String] = Some(" -P:RetrofuturagradleScalaTokenReplacement:file:/home/replacements.properties")

  private object Component extends PluginComponent with TypingTransformers with TreeDSL {
    val global: ScalaTokenReplacement.this.global.type = ScalaTokenReplacement.this.global
    val runsAfter = List[String]("parser")
    val phaseName = ScalaTokenReplacement.this.name

    def newTransformer(unit: CompilationUnit) = new TokenReplacementTransformer(unit)

    var replaced = 0

    def newPhase(prev: scala.tools.nsc.Phase): StdPhase = new Phase(prev)

    /** The phase defined by this transform */
    class Phase(prev: scala.tools.nsc.Phase) extends StdPhase(prev) {
      def apply(unit: global.CompilationUnit) {
        val uFile = unit.source.file.file.getCanonicalFile
        if (replacer.shouldReplaceInFile(uFile)) {
          replaced = 0
          newTransformer(unit).transformUnit(unit)
          if (replaced > 0) {
            println("Replaced %d tokens in %s".format(replaced, uFile.getPath))
          }
        }
      }
    }

    class TokenReplacementTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
      override def transform(tree: Tree): Tree = tree match {
        case lit@Literal(litconst@Constant(litval)) if litval.isInstanceOf[String] => {
          val strval = litconst.stringValue
          super.transform(Literal(Constant(replacer.replaceIfNeeded(strval, new ReplacedSetter()).toString)))
        }
        case _ => super.transform(tree)
      }
    }

    class ReplacedSetter() extends IntConsumer {
      override def accept(value: Int): Unit = {
        replaced += value
      }
    }
  }
}

package icfpc2013

import java.io._
import java.util.zip._
import org.apache.commons.io.IOUtils
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.slick.driver.SQLiteDriver.simple._
import Database.threadLocalSession
import spray.util._

import com.twitter.chill.KryoInjection

case class RainbowTableSolver(dbName: String = "rainbow") extends Solver {
  type State = Option[Seq[(Expression, Long => Long)]]
  val initialState = None

  val rainbowTable = RainbowTable(dbName)

  def solve(
    problemId: String,
    size: Int,
    ops: Set[Operator],
    inputId: Id = Id("x"),
    knownInputs: Map[Long, Long] = Map(),
    state: State = initialState): (Map[Long, Long], State, Option[Expression]) = rainbowTable.db withSession {

    var outputHash = knownInputs.values.##

    val inputsMap =
      if (knownInputs.nonEmpty) knownInputs
      else {
        val response = Client.eval(EvalRequest(Some(problemId), None, StaticInput.hex0x)).await
        val outputsLong = response.outputs.get.map(h => HexString.toLong(h.drop(2)))
        outputHash = outputsLong.## // This is very lame, but it's too late...

        StaticInput.long.zip(outputsLong).toMap
      }

    val possiblePrograms = state.getOrElse {
      val rainbowPrograms = (for {
        r <- RainbowTables if r.outputHash === outputHash.toString
      } yield (r.program)).list.map { bytes =>

        val out = new ByteArrayOutputStream()
        val bais = new ByteArrayInputStream(bytes)
        val gzis = new GZIPInputStream(bais)

        IOUtils.copy(gzis, out)
        gzis.close()

        // *gulp*
        val program = KryoInjection.invert(out.toByteArray).toOption.get.asInstanceOf[Program]

        (program.e, BvCompiler(program))
      }

      if (rainbowPrograms.isEmpty)
        ProgramGenerator.getPrograms(size, ops, inputId, true).map { p =>
          Future {
            RainbowTables.insert(
              problemId, p, outputHash.toString)
          }
          (p.e, BvCompiler(p))
        }
      else rainbowPrograms
    }

    val filtered = possiblePrograms.filter { program =>
      inputsMap.forall { case (in, out) => program._2(in) == out }
    }

    (inputsMap, Some(filtered), filtered.headOption.map(_._1))
  }
}

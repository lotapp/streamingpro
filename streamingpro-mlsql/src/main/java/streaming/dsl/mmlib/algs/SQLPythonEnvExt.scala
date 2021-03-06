/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streaming.dsl.mmlib.algs

import org.apache.spark.ml.param.Param
import org.apache.spark.ps.cluster.Message
import org.apache.spark.ps.cluster.Message.CreateOrRemovePythonCondaEnvResponse
import org.apache.spark.scheduler.cluster.PSDriverEndpoint
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.mlsql.session.MLSQLException
import org.apache.spark.sql.{DataFrame, SparkSession}
import streaming.common.HDFSOperator
import streaming.core.strategy.platform.{PlatformManager, SparkRuntime}
import streaming.dsl.mmlib.SQLAlg
import streaming.dsl.mmlib.algs.param.{BaseParams, WowParams}
import streaming.dsl.mmlib.algs.python.BasicCondaEnvManager

/**
  * 2019-01-16 WilliamZhu(allwefantasy@gmail.com)
  */
class SQLPythonEnvExt(override val uid: String) extends SQLAlg with WowParams {

  def this() = this(BaseParams.randomUID())

  override def train(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {
    val spark = df.sparkSession

    params.get(command.name).map { s =>
      set(command, s)
      s
    }.getOrElse {
      throw new MLSQLException(s"${command.name} is required")
    }

    params.get(condaYamlFilePath.name).map { s =>
      set(condaYamlFilePath, s)
    }.getOrElse {
      params.get(condaFile.name).map { s =>
        val condaContent = spark.table(s).head().getString(0)
        val baseFile = path + "/__mlsql_temp_dir__/conda"
        val fileName = "conda.yaml"
        HDFSOperator.saveFile(baseFile, fileName, Seq(("", condaContent)).iterator)
        set(condaYamlFilePath, baseFile + "/" + fileName)
      }.getOrElse {
        throw new MLSQLException(s"${condaFile.name} || ${condaYamlFilePath} is required")
      }

    }

    val wowCommand = $(command) match {
      case "create" => Message.AddEnvCommand
      case "remove" => Message.RemoveEnvCommand
    }
    val appName = spark.sparkContext.getConf.get("spark.app.name")
    val remoteCommand = Message.CreateOrRemovePythonCondaEnv($(condaYamlFilePath), params ++ Map(BasicCondaEnvManager.MLSQL_INSTNANCE_NAME_KEY -> appName), wowCommand)

    val response = if (spark.sparkContext.isLocal) {
      val psDriverBackend = PlatformManager.getRuntime.asInstanceOf[SparkRuntime].localSchedulerBackend
      psDriverBackend.localEndpoint.askSync[CreateOrRemovePythonCondaEnvResponse](remoteCommand, PSDriverEndpoint.MLSQL_DEFAULT_RPC_TIMEOUT(spark.sparkContext.getConf))
    } else {
      val psDriverBackend = PlatformManager.getRuntime.asInstanceOf[SparkRuntime].psDriverBackend
      psDriverBackend.psDriverRpcEndpointRef.askSync[CreateOrRemovePythonCondaEnvResponse](remoteCommand, PSDriverEndpoint.MLSQL_DEFAULT_RPC_TIMEOUT(spark.sparkContext.getConf))
    }
    import spark.implicits._
    spark.createDataset[CreateOrRemovePythonCondaEnvResponse](Seq(response)).toDF()
  }


  override def batchPredict(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {
    train(df, path, params)
  }

  override def load(sparkSession: SparkSession, path: String, params: Map[String, String]): Any = throw new RuntimeException("register is not support")

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = throw new RuntimeException("register is not support")

  final val command: Param[String] = new Param[String](this, "command", "", isValid = (s: String) => {
    s == "create" || s == "remove"
  })

  final val condaYamlFilePath: Param[String] = new Param[String](this, "condaYamlFilePath", "")
  final val condaFile: Param[String] = new Param[String](this, "condaFile", "")
}

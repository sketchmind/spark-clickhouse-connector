/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xenon.clickhouse.write

import com.clickhouse.client.{ClickHouseCompression, ClickHouseProtocol}
import net.jpountz.lz4.LZ4FrameOutputStream
import net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE
import org.apache.spark.sql.catalyst.expressions.{BoundReference, Expression, SafeProjection}
import org.apache.spark.sql.catalyst.{expressions, InternalRow}
import org.apache.spark.sql.clickhouse.{ExprUtils, JsonWriter}
import org.apache.spark.sql.connector.write.{DataWriter, WriterCommitMessage}
import org.apache.spark.sql.types.{ByteType, IntegerType, LongType, ShortType}
import xenon.clickhouse._
import xenon.clickhouse.client.{ClusterClient, NodeClient}
import xenon.clickhouse.exception._
import xenon.clickhouse.spec.{DistributedEngineSpec, ShardUtils}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success}

class ClickHouseWriter(writeJob: WriteJobDescription)
    extends DataWriter[InternalRow] with Logging {

  val database: String = writeJob.targetDatabase(writeJob.writeOptions.convertDistributedToLocal)
  val table: String = writeJob.targetTable(writeJob.writeOptions.convertDistributedToLocal)
  val codec: ClickHouseCompression = writeJob.writeOptions.compressionCodec
  val protocol: ClickHouseProtocol = writeJob.node.protocol

  private lazy val shardExpr: Option[Expression] = writeJob.sparkShardExpr match {
    case None => None
    case Some(v2Expr) =>
      val catalystExpr = ExprUtils.toCatalyst(v2Expr, writeJob.dataSetSchema.fields)
      catalystExpr match {
        case BoundReference(_, dataType, _)
            if dataType.isInstanceOf[ByteType] // list all integral types here because we can not access `IntegralType`
              || dataType.isInstanceOf[ShortType]
              || dataType.isInstanceOf[IntegerType]
              || dataType.isInstanceOf[LongType] =>
          Some(catalystExpr)
        case BoundReference(_, dataType, _) =>
          throw CHClientException(s"Invalid data type of sharding field: $dataType")
        case unsupported: Expression =>
          log.warn(s"Unsupported expression of sharding field: $unsupported")
          None
      }
  }

  private lazy val shardProjection: Option[expressions.Projection] = shardExpr
    .filter(_ => writeJob.writeOptions.convertDistributedToLocal)
    .map(expr => SafeProjection.create(Seq(expr)))

  // put the node select strategy in executor side because we need to calculate shard and don't know the records
  // util DataWriter#write(InternalRow) invoked.
  private lazy val nodesClient: Either[ClusterClient, NodeClient] =
    writeJob.tableEngineSpec match {
      case _: DistributedEngineSpec
          if writeJob.writeOptions.useClusterNodesForDistributed || writeJob.writeOptions.convertDistributedToLocal =>
        val clusterSpec = writeJob.cluster.get
        log.info(s"Connect to ClickHouse cluster ${clusterSpec.name}, which has ${clusterSpec.nodes.length} nodes.")
        Left(ClusterClient(clusterSpec))
      case _ =>
        val nodeSpec = writeJob.node
        log.info("Connect to ClickHouse single node.")
        Right(NodeClient(nodeSpec))
    }

  def nodeClient(shardNum: Option[Int]): NodeClient = nodesClient match {
    case Left(clusterClient) => clusterClient.node(shardNum)
    case Right(nodeClient) => nodeClient
  }

  var lastShardNum: Option[Int] = None
  val buf: ArrayBuffer[Array[Byte]] = new ArrayBuffer[Array[Byte]](writeJob.writeOptions.batchSize)
  val jsonWriter = new JsonWriter(writeJob.dataSetSchema, writeJob.tz)

  override def write(record: InternalRow): Unit = {
    val shardNum = calcShard(record)
    if (shardNum != lastShardNum && buf.nonEmpty) flush(lastShardNum)
    lastShardNum = shardNum
    buf += jsonWriter.row2Json(record)
    if (buf.size == writeJob.writeOptions.batchSize) flush(lastShardNum)
  }

  override def commit(): WriterCommitMessage = {
    if (buf.nonEmpty) flush(lastShardNum)
    CommitMessage(s"Job[${writeJob.queryId}]: commit")
  }

  override def abort(): Unit = close()

  override def close(): Unit = nodesClient match {
    case Left(clusterClient) => clusterClient.close()
    case Right(nodeClient) => nodeClient.close()
  }

  def calcShard(record: InternalRow): Option[Int] = (shardExpr, shardProjection) match {
    case (Some(BoundReference(_, dataType, _)), Some(projection)) =>
      val shardValue = dataType match {
        case ByteType => Some(projection(record).getByte(0).toLong)
        case ShortType => Some(projection(record).getShort(0).toLong)
        case IntegerType => Some(projection(record).getInt(0).toLong)
        case LongType => Some(projection(record).getLong(0))
        case _ => None
      }
      shardValue.map(value => ShardUtils.calcShard(writeJob.cluster.get, value).num)
    case _ => None
  }

  private val reusedByteArray = new ByteArrayOutputStream(64 * 1024 * 1024)

  private def assembleData(buf: ArrayBuffer[Array[Byte]]): Array[Byte] = {
    val out = (codec, protocol) match {
      case (ClickHouseCompression.NONE, _) =>
        reusedByteArray
      case (ClickHouseCompression.LZ4, ClickHouseProtocol.GRPC) =>
        new LZ4FrameOutputStream(reusedByteArray, BLOCKSIZE.SIZE_4MB)
      case (ClickHouseCompression.LZ4, ClickHouseProtocol.HTTP) =>
        // clickhouse http client forces compressed output stream
        reusedByteArray
      case unsupported =>
        throw CHClientException(s"unsupported compression codec: $unsupported")
    }
    buf.foreach(out.write)
    out.flush()
    out.close()
    val result = reusedByteArray.toByteArray
    reusedByteArray.reset()
    result
  }

  def flush(shardNum: Option[Int]): Unit = {
    val client = nodeClient(shardNum)
    Utils.retry[Unit, RetryableCHException](
      writeJob.writeOptions.maxRetry,
      writeJob.writeOptions.retryInterval
    ) {
      val uncompressedSize = buf.map(_.length).sum
      val startTime = System.currentTimeMillis
      val data = assembleData(buf)
      val costMs = System.currentTimeMillis - startTime
      val compressedSize = data.length
      log.info(s"""Job[${writeJob.queryId}]: prepare to flush batch
                  |cluster: ${writeJob.cluster.map(_.name).getOrElse("none")}, shard: ${shardNum.getOrElse("none")}
                  |node: ${client.nodeSpec}
                  |             rows: ${buf.size}
                  |uncompressed size: ${Utils.bytesToString(uncompressedSize)}
                  |  compressed size: ${Utils.bytesToString(compressedSize)}
                  |compression codec: $codec
                  | compression cost: ${costMs}ms
                  |""".stripMargin)
      client.syncInsertOutputJSONEachRow(
        database,
        table,
        "JSONEachRow",
        codec,
        new ByteArrayInputStream(data)
      ) match {
        case Right(_) => buf.clear
        case Left(retryable) if writeJob.writeOptions.retryableErrorCodes.contains(retryable.code) =>
          throw RetryableCHException(retryable.code, retryable.reason, Some(client.nodeSpec))
        case Left(rethrow) => throw rethrow
      }
    } match {
      case Success(_) => log.info(s"Job[${writeJob.queryId}]: flush batch completed")
      case Failure(rethrow) => throw rethrow
    }
  }
}

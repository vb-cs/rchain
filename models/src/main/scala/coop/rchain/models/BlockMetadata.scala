package coop.rchain.models

import com.google.protobuf.ByteString
import coop.rchain.casper.protocol._
import scalapb.TypeMapper

final case class BlockMetadata(
    blockHash: ByteString,
    parents: List[ByteString],
    sender: ByteString,
    justifications: List[Justification],
    weightMap: Map[ByteString, Long],
    blockNum: Long,
    seqNum: Int,
    invalid: Boolean
) {
  def toByteString = BlockMetadata.typeMapper.toBase(this).toByteString
}

object BlockMetadata {
  implicit val typeMapper = TypeMapper[BlockMetadataInternal, BlockMetadata] { internal =>
    BlockMetadata(
      internal.blockHash,
      internal.parents,
      internal.sender,
      internal.justifications.map(Justification.from),
      internal.bonds.map(b => b.validator -> b.stake).toMap,
      internal.blockNum,
      internal.seqNum,
      internal.invalid
    )
  } { metadata =>
    BlockMetadataInternal(
      metadata.blockHash,
      metadata.parents,
      metadata.sender,
      metadata.justifications.map(Justification.toProto),
      metadata.weightMap.map { case (validator, stake) => BondProto(validator, stake) }.toList,
      metadata.blockNum,
      metadata.seqNum,
      metadata.invalid
    )
  }

  private val iterableByteOrdering = Ordering.Iterable[Byte]

  private def compareByteString(l: ByteString, r: ByteString): Int =
    iterableByteOrdering.compare(l.toByteArray, r.toByteArray)

  val orderingByNum: Ordering[BlockMetadata] =
    (l: BlockMetadata, r: BlockMetadata) => {
      l.blockNum.compare(r.blockNum) match {
        case 0 => compareByteString(l.blockHash, r.blockHash)
        case v => v
      }
    }

  def fromBytes(bytes: Array[Byte]): BlockMetadata =
    typeMapper.toCustom(BlockMetadataInternal.parseFrom(bytes))

  private def weightMap(state: RChainState): Map[ByteString, Long] =
    state.bonds.map {
      case Bond(validator, stake) => validator -> stake
    }.toMap

  def fromBlock(b: BlockMessage, invalid: Boolean): BlockMetadata =
    BlockMetadata(
      b.blockHash,
      b.header.parentsHashList,
      b.sender,
      b.justifications,
      weightMap(b.body.state),
      b.body.state.blockNumber,
      b.seqNum,
      invalid
    )
}

package spark.jobserver.io

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

class BinaryObjectsDAOSpec extends AnyFunSpecLike with BeforeAndAfterAll with Matchers{

  describe("binary hash conversions") {
    it("should convert byte hash to string and back") {
      val hash = BinaryObjectsDAO.calculateBinaryHash(
        Array(1, 4, -1, 7): Array[Byte]
      )
      hash should equal (BinaryObjectsDAO.hashStringToBytes(
        BinaryObjectsDAO.hashBytesToString(hash)
      ))
    }

    it("calculate hash string equals calculate byte hash and convert it") {
      val testBytes : Array[Byte] = Array(1, 4, -1, 7)
      BinaryObjectsDAO.calculateBinaryHashString(testBytes) should equal(
        BinaryObjectsDAO.hashBytesToString(BinaryObjectsDAO.calculateBinaryHash(testBytes))
      )
    }

    it("should create correct hex hash string") {
      val testBytes = "Test string".toCharArray.map(_.toByte)
      BinaryObjectsDAO.calculateBinaryHashString(testBytes) should
        equal("a3e49d843df13c2e2a7786f6ecd7e0d184f45d718d1ac1a8a63e570466e489dd")
    }

    it("taking hash string equals taking byte hash and converting to string") {
      val testBytes = "Test string".toCharArray.map(_.toByte)
      BinaryObjectsDAO.calculateBinaryHashString(testBytes) should equal (
        BinaryObjectsDAO.hashBytesToString(BinaryObjectsDAO.calculateBinaryHash(testBytes))
      )
    }
  }
}

package io.mycat.proxy.packet;

import io.mycat.MycatExpection;
import io.mycat.beans.mysql.MySQLPayloadWriter;
import io.mycat.beans.mysql.packet.MySQLPacketSplitter;
import io.mycat.beans.mysql.packet.MySQLPayloadWriteView;
import io.mycat.beans.mysql.packet.PacketSplitterImpl;
import io.mycat.proxy.MycatReactorThread;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * @author jamie12221
 * @date 2019-05-07 21:23
 **/
public class MySQLPacketUtil {

  private static final byte NULL_MARK = (byte) 251;
  private static final byte EMPTY_MARK = (byte) 0;

  public static final byte[] generateRequest(int head, byte[] data) {
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(1 + data.length)) {
      writer.write(head);
      writer.write(data);
      return writer.toByteArray();
    }
  }

  public static final byte[] generateComQueryPacket(String sql) {
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(sql.length() + 5)) {
      writer.write(0x3);
      writer.writeEOFString(sql);
      return generateMySQLPacket(0, writer.toByteArray());
    }
  }

  public static final byte[] generateRequestPacket(int head, byte[] data) {
    byte[] bytes = generateRequest(head, data);
    return generateMySQLPacket(0, bytes);
  }

  public static final byte[] generateResultSetCount(int fieldCount) {
    MySQLPayloadWriter writer = new MySQLPayloadWriter(1);
    writer.writeLenencInt(fieldCount);
    return writer.toByteArray();
  }

  public static final byte[] generateColumnDef(String name, int type, int charsetIndex,
      Charset charset) {
    return generateColumnDef(name, name, type, 0, 0, charsetIndex, charset);
  }

  public static final byte[] generateEof(
      int warningCount, int status
  ) {
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(12)) {
      writer.writeByte(0xfe);
      writer.writeFixInt(2, warningCount);
      writer.writeFixInt(2, status);
      return writer.toByteArray();
    }
  }

  public static final byte[] generateOk(int header,
      int warningCount, int serverStatus, long affectedRows, long lastInsertId,
      boolean isClientProtocol41, boolean isKnowsAboutTransactions,
      boolean sessionVariableTracking, String message
  ) {
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(12)) {
      writer.writeByte((byte) header);
      writer.writeLenencInt(affectedRows);
      writer.writeLenencInt(lastInsertId);
      if (isClientProtocol41) {
        writer.writeFixInt(2, serverStatus);
        writer.writeFixInt(2, warningCount);
      } else if (isKnowsAboutTransactions) {
        writer.writeFixInt(2, serverStatus);
      }
      if (sessionVariableTracking) {
        throw new MycatExpection("unsupport!!");
      } else {
        if (message != null) {
          writer.writeBytes(message.getBytes());
        }
      }
      return writer.toByteArray();
    }
  }

  public static final byte[] generateError(
      int errno,
      String message, int serverCapabilityFlags
  ) {
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(64)) {
      ErrorPacketImpl errorPacket = new ErrorPacketImpl();
      errorPacket.setErrorMessage(message.getBytes());
      errorPacket.setErrorCode(errno);
      errorPacket.writePayload(writer, serverCapabilityFlags);
      return writer.toByteArray();
    }
  }

  public static final byte[] generateProgressInfoErrorPacket(
      int stage, int maxStage, int progress, byte[] progressInfo
  ) {
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(64)) {
      ErrorPacketImpl errorPacket = new ErrorPacketImpl();
      errorPacket.setErrorCode(0xFFFF);
      errorPacket.setErrorStage(stage);
      errorPacket.setErrorMaxStage(maxStage);
      errorPacket.setErrorProgress(progress);
      errorPacket.setErrorProgressInfo(progressInfo);
      return writer.toByteArray();
    }
  }

  public static final byte[] generateBinaryRow(
      byte[][] rows) {
    final int columnCount = rows.length;
    final int binaryNullBitMapLength = (columnCount + 7 + 2) / 8;
    byte[] nullMap = new byte[binaryNullBitMapLength];
    final int payloayEstimateMaxSize = generateBinaryRowHeader(rows, nullMap);
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(payloayEstimateMaxSize)) {
      writer.writeBytes(nullMap);
      nullMap = null;
      for (byte[] row : rows) {
        if (row != null) {
          writer.writeLenencBytes(row);
        }
      }
      return writer.toByteArray();
    }
  }

  private static int generateBinaryRowHeader(byte[][] rows, byte[] nullMap) {
    int columnIndex = 0;
    int payloayEstimateMaxSize = 0;
    for (byte[] row : rows) {
      if (row != null) {
        payloayEstimateMaxSize += row.length;
        payloayEstimateMaxSize += MySQLPacket.getLenencLength(row.length);
      } else {
        int i = (columnIndex + 2) / 8;
        byte aByte = nullMap[i];
        nullMap[i] = (byte) (aByte | (1 << (columnIndex & 7)));
      }
      columnIndex++;
    }
    return payloayEstimateMaxSize;
  }

  public static final byte[] generateColumnDef(String name, String orgName, int type,
      int columnFlags,
      int columnDecimals, int charsetIndex, Charset charset) {
    ColumnDefPacketImpl c = new ColumnDefPacketImpl();
    c.setColumnCharsetSet(charsetIndex);
    c.setColumnName(encode(name, charset));
    c.setColumnOrgName(encode(orgName, charset));
    c.setColumnType(type);
    c.setColumnFlags(columnFlags);
    c.setColumnDecimals((byte) columnDecimals);
    MySQLPayloadWriter writer = new MySQLPayloadWriter(64);
    c.writePayload(writer);
    return writer.toByteArray();
  }

  public static byte[] generateMySQLCommandRequest(int packetId, byte head, byte[] packet) {
    try (MySQLPayloadWriter byteArrayOutput = new MySQLPayloadWriter(1 + packet.length)) {
      byteArrayOutput.write(head);
      byteArrayOutput.write(packet);
      byte[] bytes = byteArrayOutput.toByteArray();
      return generateMySQLPacket(packetId, bytes);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] generateMySQLPacket(int packetId, MySQLPayloadWriter writer) {
    byte[] bytes = writer.toByteArray();
    try {
      MycatReactorThread reactorThread = (MycatReactorThread) Thread.currentThread();
      PacketSplitterImpl packetSplitter = reactorThread.getPacketSplitter();
      int wholePacketSize = MySQLPacketSplitter.caculWholePacketSize(bytes.length);
      try (MySQLPayloadWriter byteArray = new MySQLPayloadWriter(
          wholePacketSize)) {
        packetSplitter.init(bytes.length);
        while (packetSplitter.nextPacketInPacketSplitter()) {
          int offset = packetSplitter.getOffsetInPacketSplitter();
          int len = packetSplitter.getPacketLenInPacketSplitter();
          byteArray.writeFixInt(3, len);
          byteArray.write(packetId);
          byteArray.write(bytes, offset, len);
          ++packetId;
        }
        return byteArray.toByteArray();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] generateMySQLPacket(int packetId, byte[] packet) {
    try {
      MycatReactorThread reactorThread = (MycatReactorThread) Thread.currentThread();
      PacketSplitterImpl packetSplitter = reactorThread.getPacketSplitter();
      int wholePacketSize = MySQLPacketSplitter.caculWholePacketSize(packet.length);
      try (MySQLPayloadWriter byteArray = new MySQLPayloadWriter(
          wholePacketSize)) {
        packetSplitter.init(packet.length);
        while (packetSplitter.nextPacketInPacketSplitter()) {
          int offset = packetSplitter.getOffsetInPacketSplitter();
          int len = packetSplitter.getPacketLenInPacketSplitter();
          byteArray.writeFixInt(3, len);
          byteArray.write(packetId);
          byteArray.write(packet, offset, len);
          ++packetId;
        }
        return byteArray.toByteArray();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] encode(String src, String charset) {
    if (src == null) {
      return null;
    }
    try {
      return src.getBytes(charset);
    } catch (UnsupportedEncodingException e) {
      return src.getBytes();
    }
  }

  public static byte[] encode(String src, Charset charset) {
    if (src == null) {
      return null;
    }
    return src.getBytes(charset);
  }


  public static int calcTextRowPayloadSize(byte[][] fieldValues) {
    int size = 0;
    int fieldCount = fieldValues.length;
    for (int i = 0; i < fieldCount; i++) {
      byte[] v = fieldValues[i];
      size += (v == null || v.length == 0) ? 1 : MySQLPacket.getLenencLength(v.length);
    }
    return size;
  }

  public static void writeTextRow(byte[][] fieldValues, MySQLPayloadWriteView writer) {
    int fieldCount = fieldValues.length;
    for (int i = 0; i < fieldCount; i++) {
      byte[] fv = fieldValues[i];
      if (fv == null) {
        writer.writeByte(NULL_MARK);
      } else if (fv.length == 0) {
        writer.writeByte(EMPTY_MARK);
      } else {
        writer.writeLenencBytes(fv);
      }
    }
  }

  public static final byte[] generateTextRow(byte[][] fieldValues) {
    int len = calcTextRowPayloadSize(fieldValues);
    try (MySQLPayloadWriter writer = new MySQLPayloadWriter(len)) {
      writeTextRow(fieldValues, writer);
      return writer.toByteArray();
    }
  }
}
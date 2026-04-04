package com.kiosk.crt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class Utils {

  private Utils() {
    // Prevent instantiation
  }

  /* ================= STRING ================= */

  public static String trimAll(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().replace(" ", "");
  }

  public static String getOrNull(Map<Integer, List<String>> map, int key, int index) {
    if (map == null) {
      return null;
    }

    List<String> list = map.get(key);
    if (list == null || index >= list.size()) {
      return null;
    }

    return list.get(index);
  }

  /* ================= HEX ================= */

  private static final String HEX_STR = "0123456789ABCDEF";
  private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

  public static byte[] hexStr2ByteArrs(String hexString) {
    String temp = hexString.toUpperCase();
    int len = temp.length() / 2;
    byte[] bytes = new byte[len];

    for (int i = 0; i < len; i++) {
      int high = HEX_STR.indexOf(temp.charAt(i * 2)) << 4;
      int low = HEX_STR.indexOf(temp.charAt(i * 2 + 1));
      bytes[i] = (byte) (high | low);
    }
    return bytes;
  }

  public static String bytes2HexStr(byte[] bytes, int len, boolean space) {
    if (bytes == null || len > bytes.length) {
      return "";
    }

    StringBuilder sb = new StringBuilder(len * (space ? 3 : 2));
    for (int i = 0; i < len; i++) {
      int v = bytes[i] & 0xFF;
      sb.append(HEX_ARRAY[v >>> 4]);
      sb.append(HEX_ARRAY[v & 0x0F]);
      if (space) {
        sb.append(' ');
      }
    }
    return sb.toString().trim();
  }

  public static byte[] readAllBytes(InputStream is) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[4096];
    int nRead;
    while ((nRead = is.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, nRead);
    }
    return buffer.toByteArray();
  }

  public static int dgNumberFromTag(int tag) {
    switch (tag) {
      case 0x61:
        return 1;
      case 0x75:
        return 2;
      case 0x63:
        return 3;
      case 0x76:
        return 4;
      default:
        if (tag >= 0x65 && tag <= 0x70) {
          return tag - 0x60;
        }
        return -1;
    }
  }
}
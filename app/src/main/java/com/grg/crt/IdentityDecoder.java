package com.grg.crt;

import org.bouncycastle.asn1.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import android.util.Base64;

public class IdentityDecoder {

  public static Map<Integer, List<String>> parse(String base64) throws Exception {
    byte[] data = Base64.decode(base64, android.util.Base64.DEFAULT);
    ASN1Primitive root = ASN1Primitive.fromByteArray(data);
    Map<Integer, List<String>> fields = new LinkedHashMap<>();
    collectFields(root, fields);
    return fields;
  }

  public static Map<Integer, List<String>> parse() throws Exception {
    String base64 = "bYIBlDCCAZACAQEGBijTFgEACDGCAYEwEQIBARMMMDI3MjAzMDAwMzA3"
        + "MBwCAQIMF05ndXnhu4VuIMSQw6xuaCDDmnQgQml1MA8CAQMTCjI3LzAz"
        + "LzIwMDMwCAIBBAwDTmFtMA8CAQUMClZp4buHdCBOYW0wCQIBBgwES2lu"
        + "aDALAgEHDAZLaMO0bmcwJwIBCAwiTMOjbmcgTmfDom0sIEdpYSBCw6xu"
        + "aCwgQuG6r2MgTmluaDA8AgEJDDdUaMO0biBOZ8SDbSBMxrDGoW5nLCBM"
        + "w6NuZyBOZ8OibSwgR2lhIELDrG5oLCBC4bqvYyBOaW5oMC8CAQoMKk7h"
        + "u5F0IHJ14buTaSBDOiAxY20gZMaw4bubaSBzYXUgbcOpcCB0csOhaTAP"
        + "AgELEwoyNC8wOC8yMDIyMA8CAQwMCjI3LzAzLzIwMjgwLwIBDTAYDBZO"
        + "Z3V54buFbiDEkMOsbmggSHV5w6puMBAMDk5nw7QgVGjhu4sgVGh1MAMC"
        + "AQ4wAwIBDzAVAgEQExAwMjk0MDExRDI1MzQwMDAw";
    return parse(base64);
  }

  private static void collectFields(ASN1Primitive obj, Map<Integer, List<String>> out) {
    if (obj instanceof ASN1TaggedObject) {
      collectFields(((ASN1TaggedObject) obj).getObject().toASN1Primitive(), out);
      return;
    }

    if (obj instanceof ASN1Sequence) {
      ASN1Sequence seq = (ASN1Sequence) obj;

      // Field ::= SEQUENCE { INTEGER, value }
      if (seq.size() >= 1 && seq.getObjectAt(0) instanceof ASN1Integer) {
        int id = ((ASN1Integer) seq.getObjectAt(0)).getValue().intValue();

        // Collect ALL strings from the rest of the sequence
        List<String> allStrings = new ArrayList<>();
        for (int i = 1; i < seq.size(); i++) {
          extractAllStrings(seq.getObjectAt(i), allStrings);
        }

        // Store all strings in the list
        if (!allStrings.isEmpty()) {
          out.put(id, allStrings);
        }
      }

      // Continue traversal for all elements
      Enumeration<?> e = seq.getObjects();
      while (e.hasMoreElements()) {
        ASN1Encodable enc = (ASN1Encodable) e.nextElement();
        collectFields(enc.toASN1Primitive(), out);
      }
      return;
    }

    if (obj instanceof ASN1Set) {
      Enumeration<?> e = ((ASN1Set) obj).getObjects();
      while (e.hasMoreElements()) {
        ASN1Encodable enc = (ASN1Encodable) e.nextElement();
        collectFields(enc.toASN1Primitive(), out);
      }
    }
  }

  private static void extractAllStrings(ASN1Encodable obj, List<String> out) {
    if (obj == null) {
      return;
    }

    if (obj instanceof ASN1String) {
      String s = ((ASN1String) obj).getString();
      if (!s.isEmpty()) {
        out.add(s);
      }
      return;
    }

    if (obj instanceof ASN1TaggedObject) {
      extractAllStrings(((ASN1TaggedObject) obj).getObject(), out);
      return;
    }

    if (obj instanceof ASN1OctetString) {
      try {
        ASN1Primitive p = ASN1Primitive.fromByteArray(((ASN1OctetString) obj).getOctets());
        extractAllStrings(p, out);
      } catch (Exception ignored) {
        try {
          String s = new String(((ASN1OctetString) obj).getOctets(), StandardCharsets.UTF_8);
          if (!s.isEmpty()) {
            out.add(s);
          }
        } catch (Exception ignored2) {
        }
      }
      return;
    }

    if (obj instanceof ASN1Sequence) {
      for (ASN1Encodable e : (ASN1Sequence) obj) {
        extractAllStrings(e, out);
      }
      return;
    }

    if (obj instanceof ASN1Set) {
      for (ASN1Encodable e : (ASN1Set) obj) {
        extractAllStrings(e, out);
      }
    }
  }
}

package com.grg.crt;

import android.util.Base64;
import android.util.Log;
import com.alibaba.fastjson.JSONObject;
import com.device.Crt900x;
import com.device.CrtPassportReader;
import com.grg.grglog.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.LDSFileUtil;
import org.jmrtd.lds.SODFile;
import org.jmrtd.lds.icao.COMFile;
import org.jmrtd.lds.icao.DG2File;
import org.jmrtd.lds.icao.MRZInfo;
import org.jmrtd.lds.iso19794.FaceImageInfo;

public class SimplePassportReader {

  private static final String TAG = "SimplePassportReader";

  private final Crt900x crt900xNative;
  private final String readerFd;

  public SimplePassportReader(Crt900x crt900x, String readerFd) {
    this.crt900xNative = crt900x;
    this.readerFd = readerFd;
  }

  private void dummy() {
    int[] respLen = new int[1];
    byte[] resp = new byte[256];
    crt900xNative.CrtSendAPDU('A', Utils.hexStr2ByteArrs("00 70 80 00").length,
        Utils.hexStr2ByteArrs("00 70 80 00"), respLen, resp);

    if (resp.length < 2) {
      Log.d(TAG, "Channel close SW: " + resp);
    } else {
      Log.d(TAG, "Channel close SW: " + String.format("%02X%02X", resp[respLen[0] - 2],
          resp[respLen[0] - 1]));
    }
  }

  public int crtReadCardDG(String mrz) {
    try {
      byte[] info = new byte[1024];
      char[] cKeys = mrz.toCharArray();
      LogUtils.e(TAG, "开始读卡===》");
      int iRet = this.crt900xNative.CrtReaderReadCardInfo(cKeys, info);
      LogUtils.e(TAG, "读卡结束==》" + iRet);
      if (iRet != 0) {
        if (iRet == -31) {
          LogUtils.e(TAG, "mrz wrong");
        } else if (iRet == -32) {
          LogUtils.e(TAG, "Read failed!");
        } else {
          LogUtils.e(TAG, "Read text failed");
        }

        return -106;
      } else {
        return 0;
      }
    } catch (Exception e) {
      LogUtils.e(TAG, "Error in readCardDG" + e);
      return -1;
    }
  }

  public BACKeySpec getBACKey(String mrz) {
    MRZInfo mrzInfo = new MRZInfo(mrz);
    BACKeySpec bacKey = new BACKey(mrzInfo.getDocumentNumber(), mrzInfo.getDateOfBirth(),
        mrzInfo.getDateOfExpiry());

    return bacKey;
  }

  public void getAllData(String mrz, OnGetAllDataResult result) {
    new Thread(() -> {
      try {
        // ? sdk could hide some logic for reader
        crtReadCardDG(mrz);
        Log.d(TAG, "BAC Started: " + "mrz=" + mrz);

        CrtCardService cardService = new CrtCardService(crt900xNative, readerFd);
        PassportService passportService = new PassportService(cardService, 256, 256, 224, false,
            true);

        dummy(); // reset previous secure message
        BACKeySpec bacKey = getBACKey(mrz);
        passportService.close();
        passportService.open();
        passportService.doBAC(bacKey);
        Log.d(TAG, "BAC SUCCESS");
        passportService.sendSelectApplet(true);

        JSONObject verifyObjectData = new JSONObject();
        JSONObject rawObject = new JSONObject();

        // ---- EF.COM ----
        byte[] comBytes;
        try (InputStream is = passportService.getInputStream(PassportService.EF_COM)) {
          comBytes = Utils.readAllBytes(is);
        }

        COMFile comFile = new COMFile(new ByteArrayInputStream(comBytes));
        String comBase64 = Base64.encodeToString(comBytes, Base64.NO_WRAP);
        Log.d(TAG, "COM: " + comBase64);
        rawObject.put("com", comBase64);

        // ---- EF.SOD ----
        byte[] sodBytes;
        try (InputStream is = passportService.getInputStream(PassportService.EF_SOD)) {
          sodBytes = Utils.readAllBytes(is);
        }

        SODFile sodFile = new SODFile(new ByteArrayInputStream(sodBytes));
        String sodBase64 = Base64.encodeToString(sodBytes, Base64.NO_WRAP);
        Log.d(TAG, "SOD: " + sodBase64);
        rawObject.put("sod", sodBase64);

        // ---- DG ----
        int[] dgTags = comFile.getTagList();

        for (int tag : dgTags) {
          short fid = LDSFileUtil.lookupFIDByTag(tag);

          if (tag == 0x63 || tag == 0x76) {
            Log.d(TAG, "Skipping EAC-protected DG: 0x" + Integer.toHexString(tag));
            continue;
          }

          try (InputStream dgIn = passportService.getInputStream(fid)) {
            String dgName = "dg" + Utils.dgNumberFromTag(tag);
            byte[] bytes = Utils.readAllBytes(dgIn);
            String dgBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);

            Log.d(TAG, dgName + " " + dgBase64);
            rawObject.put(dgName, dgBase64);
          }
        }

        // general object data
        verifyObjectData.put("raw", rawObject);
        JSONObject dataObject = new JSONObject();

        String encodedVerifyObject = Base64.encodeToString(
            verifyObjectData.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        dataObject.put("dataVerifyObject", encodedVerifyObject);

        // portrait (DG2)
        byte[] dg2Bytes;
        try (InputStream is = passportService.getInputStream(PassportService.EF_DG2)) {
          dg2Bytes = Utils.readAllBytes(is);
        }

        DG2File dg2File = new DG2File(new ByteArrayInputStream(dg2Bytes));
        FaceImageInfo faceImageInfo = dg2File.getFaceInfos().get(0).getFaceImageInfos().get(0);

        byte[] imageBytes;
        try (InputStream imgStream = faceImageInfo.getImageInputStream()) {
          imageBytes = Utils.readAllBytes(imgStream);
        }

        dataObject.put("nfcPortrait", Base64.encodeToString(imageBytes, Base64.NO_WRAP));

        String dgCertBase64 = Base64.encodeToString(sodFile.getDocSigningCertificate().getEncoded(),
            Base64.NO_WRAP);
        dataObject.put("dgCert", dgCertBase64);

        // identity data
        JSONObject identityData = new JSONObject();

        byte[] dg1Bytes;
        try (InputStream is = passportService.getInputStream(PassportService.EF_DG1)) {
          dg1Bytes = Utils.readAllBytes(is);
        }

        String mrzText = new String(dg1Bytes, StandardCharsets.ISO_8859_1);

        byte[] dg13Bytes;
        try (InputStream is = passportService.getInputStream(PassportService.EF_DG13)) {
          dg13Bytes = Utils.readAllBytes(is);
        }

        String dg13Base64 = Base64.encodeToString(dg13Bytes, Base64.NO_WRAP);
        Map<Integer, List<String>> identityValues = IdentityDecoder.parse(dg13Base64);

        identityData.put("mrz", mrzText);
        identityData.put("cardNumber", Utils.getOrNull(identityValues, 1, 0));
        identityData.put("name", Utils.getOrNull(identityValues, 2, 0));
        identityData.put("dateOfBirth", Utils.getOrNull(identityValues, 3, 0));
        identityData.put("sex", Utils.getOrNull(identityValues, 4, 0));
        identityData.put("nationality", Utils.getOrNull(identityValues, 6, 0));
        identityData.put("religion", Utils.getOrNull(identityValues, 7, 0));
        identityData.put("hometown", Utils.getOrNull(identityValues, 8, 0));
        identityData.put("address", Utils.getOrNull(identityValues, 9, 0));
        identityData.put("issueDate", Utils.getOrNull(identityValues, 11, 0));
        identityData.put("expireDate", Utils.getOrNull(identityValues, 12, 0));
        identityData.put("fatherName", Utils.getOrNull(identityValues, 13, 0));
        identityData.put("motherName", Utils.getOrNull(identityValues, 13, 1));

        dataObject.put("identityData", identityData);

        // ---- Build human-readable summary for the TextView ----
        StringBuilder sb = new StringBuilder();
        sb.append("=== NFC Chip Data ===\n\n");

        sb.append("[ Identity ]\n");
        appendField(sb, "Card No", getJsonStr(identityData, "cardNumber"));
        appendField(sb, "Name", getJsonStr(identityData, "name"));
        appendField(sb, "DOB", getJsonStr(identityData, "dateOfBirth"));
        appendField(sb, "Sex", getJsonStr(identityData, "sex"));
        appendField(sb, "Nationality", getJsonStr(identityData, "nationality"));
        appendField(sb, "Religion", getJsonStr(identityData, "religion"));
        appendField(sb, "Hometown", getJsonStr(identityData, "hometown"));
        appendField(sb, "Address", getJsonStr(identityData, "address"));
        appendField(sb, "Issue date", getJsonStr(identityData, "issueDate"));
        appendField(sb, "Expiry", getJsonStr(identityData, "expireDate"));
        appendField(sb, "Father", getJsonStr(identityData, "fatherName"));
        appendField(sb, "Mother", getJsonStr(identityData, "motherName"));

        sb.append("\n[ Data Groups read ]\n");
        JSONObject raw = verifyObjectData.getJSONObject("raw");
        for (String key : raw.keySet()) {
          sb.append("  ").append(key.toUpperCase()).append("\n");
        }

        sb.append("\n[ Document signing cert ]\n");
        sb.append("  ").append(sodFile.getDocSigningCertificate().getSubjectDN()).append("\n");

        Log.d(TAG, dataObject.toString());
        result.onSuccess(sb.toString());
      } catch (Exception e) {
        Log.e(TAG, "BAC FAILED, " + e);
        result.onError(-1, e.getMessage());
      } finally {
        dummy(); // reset current secure message
      }
    }).start();
  }

  // -----------------------------------------------------------------------
  // Callback interface
  // -----------------------------------------------------------------------

  public interface OnGetAllDataResult {

    void onSuccess(String dgData);

    void onError(int code, String message);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private String getJsonStr(JSONObject obj, String key) {
    String val = obj.getString(key);
    return val != null ? val : "";
  }

  private void appendField(StringBuilder sb, String label, String value) {
    if (value != null && !value.isEmpty()) {
      sb.append(String.format("  %-12s: %s\n", label, value));
    }
  }
}
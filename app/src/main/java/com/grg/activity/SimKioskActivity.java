package com.grg.activity;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.crt.Crtdriver;

import com.grg.crt.Utils;
import com.grg.test.R;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SimKioskActivity
 * <p>
 * Drives a CRT-591M SIM-card kiosk via the Crtdriver SDK.
 * <p>
 * Command reference (CRT-591M protocol): All commands are sent as hex strings: [ADDR 1B][CMD
 * 2B][SUB 2B][DATA...] Response byte[0] == 0x50  → success
 * <p>
 * ── Card-transport control ────────────────────────────────────────────── [addr]433030   Reset +
 * eject  (move card to exit gate) [addr]433031   Reset + recycle (move card to recycle bin)
 * [addr]433033   Reset, do NOT move card [addr]433230   Move to "card-hold" position [addr]433231
 * Move to IC-contact position [addr]433232   Move to RF position [addr]433233   Move to recycle-bin
 * [addr]433239   Move to "no-card-hold" position (fully retract) [addr]433240   Wait for card at
 * exit gate (user takes card) [addr]433330   Enable card entry from exit gate [addr]433331 Disable
 * card entry from exit gate
 * <p>
 * ── Status / query ────────────────────────────────────────────────────── [addr]433130   Query
 * device status  → byte[6] = status flags [addr]433131   Query sensors        → byte[6..] = sensor
 * bitmap [addr]43A230   Read device serial number [addr]43A330   Read device config [addr]43A430
 * Read firmware version
 * <p>
 * ── IC / SAM card ─────────────────────────────────────────────────────── [addr]435130[VCC]  IC
 * cold-reset  VCC: 30=5V 33=3V [addr]435138       IC warm-reset [addr]435131       IC deactivate
 * [addr]4351[T][APDU] Send APDU  T: 33=T=0, 34=T=1
 * <p>
 * ── SIM-specific ──────────────────────────────────────────────────────── After cold-reset
 * succeeds the card is in IC position, T=0 by default.
 * <p>
 * Read ICCID: SELECT + READ BINARY on EF_ICCID (ISO 7816-4) SELECT DF_TELECOM: 00A40000 02 7F10
 * SELECT EF_ICCID  : 00A40000 02 2FE2    (MF level, no DF needed for ICCID) READ BINARY      :
 * 00B00000 0A          (read 10 bytes = 20 BCD digits)
 * <p>
 * ── Slot / holder count ───────────────────────────────────────────────── The 591M exposes a
 * single transport lane; "slot count" is determined by reading the sensor bitmap and checking how
 * many card-present sensors are active.  We probe slots by cycling each position and checking
 * sensor S1. For a multi-slot holder (carousel or stacker) the host software keeps a logical count;
 * here we provide a "Count cards in holder" routine that steps through positions and detects
 * presence via the sensor query.
 */
public class SimKioskActivity extends Activity {

  private static final String TAG = "SimKiosk";

  // ── SDK instance ──────────────────────────────────────────────────────
  private Crtdriver crt = Crtdriver.getInstance();

  // ── State ─────────────────────────────────────────────────────────────
  private int n_Fd = 0;
  private String addr = "00";   // device address byte, hex
  private String portName = "/dev/ttyS5";
  private int baud = 115200;
  private int cardType = 0;   // 0=T=0, 1=T=1  (set after cold-reset)
  private boolean cardInIcPos = false;
  private boolean connected = false;

  // ── UI ────────────────────────────────────────────────────────────────
  private TextView tvLog;
  private TextView tvIccid;
  private TextView tvStatus;
  private TextView tvSlotCount;
  private TextView tvConnInfo;

  private Button btnConnect;
  private Button btnDisconnect;
  private Button btnCountSlots;
  private Button btnPushToFront;   // eject to exit gate
  private Button btnRetract;       // pull card back / recycle
  private Button btnReadIccid;
  private Button btnColdReset;
  private Button btnDeactivate;
  private Button btnQueryStatus;
  private Button btnQuerySensors;
  private Button btnFirmware;
  private Button btnClearLog;

  private Spinner spinnerPort;
  private Spinner spinnerBaud;
  private Spinner spinnerAddr;

  // ── Handler ───────────────────────────────────────────────────────────
  private Handler mHandler;

  // ─────────────────────────────────────────────────────────────────────
  //  Lifecycle
  // ─────────────────────────────────────────────────────────────────────

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_sim_kiosk);

    Process process;
    try {
      process = Runtime.getRuntime().exec("su");
      DataOutputStream os = new DataOutputStream(process.getOutputStream());
      os.writeBytes("chown root:root /dev/tty* \n");
      os.writeBytes("chmod 777 /dev/tty* \n");
      os.writeBytes("exit\n");
      os.flush();
      os.close();
      process.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    crt = new Crtdriver();
    Log.e(TAG, crt.getVersion());

    bindViews();
    setupHandler();
    setupListeners();
    setConnected(false);

    log("SimKiosk ready.");
    log("Port=" + portName + "  Baud=" + baud + "  Addr=" + addr);
    log("Press CONNECT to open the serial port.");
  }

  // ─────────────────────────────────────────────────────────────────────
  //  View binding
  // ─────────────────────────────────────────────────────────────────────

  private void bindViews() {
    tvLog = findViewById(R.id.tvSimLog);
    tvIccid = findViewById(R.id.tvIccid);
    tvStatus = findViewById(R.id.tvSimStatus);
    tvSlotCount = findViewById(R.id.tvSlotCount);
    tvConnInfo = findViewById(R.id.tvConnInfo);

    btnConnect = findViewById(R.id.btnSimConnect);
    btnDisconnect = findViewById(R.id.btnSimDisconnect);
    btnCountSlots = findViewById(R.id.btnCountSlots);
    btnPushToFront = findViewById(R.id.btnPushToFront);
    btnRetract = findViewById(R.id.btnRetract);
    btnReadIccid = findViewById(R.id.btnReadIccid);
    btnColdReset = findViewById(R.id.btnSimColdReset);
    btnDeactivate = findViewById(R.id.btnSimDeactivate);
    btnQueryStatus = findViewById(R.id.btnSimQueryStatus);
    btnQuerySensors = findViewById(R.id.btnSimQuerySensors);
    btnFirmware = findViewById(R.id.btnSimFirmware);
    btnClearLog = findViewById(R.id.btnSimClearLog);

    // ── Spinners ──────────────────────────────────────────────────────
    spinnerPort = findViewById(R.id.spinnerSimPort);
    spinnerBaud = findViewById(R.id.spinnerSimBaud);
    spinnerAddr = findViewById(R.id.spinnerSimAddr);

    // Port — scan /dev/ for tty* entries (same as MainActivity)
    String[] ports = getSerialPortNames();
    spinnerPort.setAdapter(
        new ArrayAdapter<>(this, R.layout.spinner_item, ports));
    // Pre-select the port passed in via Intent if present
    String intentPort = portName.replace("/dev/", "");
    for (int i = 0; i < ports.length; i++) {
      if (ports[i].equals(intentPort)) {
        spinnerPort.setSelection(i);
        break;
      }
    }

    // Baud
    String[] bauds = {"9600", "19200", "38400", "57600", "115200"};
    spinnerBaud.setAdapter(
        new ArrayAdapter<>(this, R.layout.spinner_item, bauds));
    // Pre-select the baud passed in via Intent
    String intentBaud = String.valueOf(baud);
    for (int i = 0; i < bauds.length; i++) {
      if (bauds[i].equals(intentBaud)) {
        spinnerBaud.setSelection(i);
        break;
      }
    }

    // Address
    String[] addrs = {"00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "0A", "0B", "0C",
        "0D", "0E", "0F"};
    spinnerAddr.setAdapter(
        new ArrayAdapter<>(this, R.layout.spinner_item, addrs));
    // Pre-select the addr passed in via Intent
    for (int i = 0; i < addrs.length; i++) {
      if (addrs[i].equalsIgnoreCase(addr)) {
        spinnerAddr.setSelection(i);
        break;
      }
    }

    tvLog.setMovementMethod(new ScrollingMovementMethod());
  }

  static String[] getSerialPortNames() {
    List<String> names = new ArrayList<>();
    File dev = new File("/dev/");
    File[] files = dev.listFiles();

    if (files != null) {
      for (File f : files) {
        if (f.getName().contains("tty")) {
          names.add(f.getName());
        }
      }
    }

    if (names.isEmpty()) {
      names.add("ttyS5"); // fallback
    }

    return names.toArray(new String[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Handler (UI-thread message relay)
  // ─────────────────────────────────────────────────────────────────────

  private void setupHandler() {
    mHandler = new Handler(msg -> {
      if (msg.obj instanceof String) {
        tvLog.append((String) msg.obj);
        // auto-scroll
        int offset = tvLog.getLineCount() * tvLog.getLineHeight();
        if (offset > tvLog.getHeight()) {
          tvLog.scrollTo(0, offset - tvLog.getHeight());
        }
      }
      return true;
    });
  }

  private void log(String s) {
    Message msg = Message.obtain(mHandler);
    msg.obj = s + "\n";
    mHandler.sendMessage(msg);
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Listeners
  // ─────────────────────────────────────────────────────────────────────

  private void setupListeners() {

    // Connect — open serial port
    btnConnect.setOnClickListener(v -> runInThread(this::doConnect));

    // Disconnect — close serial port
    btnDisconnect.setOnClickListener(v -> runInThread(this::doDisconnect));

    // Count cards in holder
    btnCountSlots.setOnClickListener(v -> runInThread(this::doCountSlots));

    // Push card to exit gate (user can take it)
    btnPushToFront.setOnClickListener(v -> runInThread(this::doPushToFront));

    // Return / retract card to recycle position
    btnRetract.setOnClickListener(v -> runInThread(this::doRetractCard));

    // Activate card (cold-reset) then read ICCID
    btnReadIccid.setOnClickListener(v -> runInThread(this::doReadIccid));

    // Cold-reset only
    btnColdReset.setOnClickListener(v -> runInThread(this::doColdReset));

    // Deactivate card (power off contacts)
    btnDeactivate.setOnClickListener(v -> runInThread(this::doDeactivate));

    // Query device status
    btnQueryStatus.setOnClickListener(v -> runInThread(this::doQueryStatus));

    // Query sensors
    btnQuerySensors.setOnClickListener(v -> runInThread(this::doQuerySensors));

    // Read firmware version
    btnFirmware.setOnClickListener(v -> runInThread(this::doFirmwareVersion));

    // Clear log
    btnClearLog.setOnClickListener(v -> runOnUiThread(() -> tvLog.setText("")));
  }

  /**
   * Enable/disable all operation buttons based on connection state.
   */
  private void setConnected(boolean isConnected) {
    connected = isConnected;
    runOnUiThread(() -> {
      // Spinners only editable while disconnected
      spinnerPort.setEnabled(!isConnected);
      spinnerBaud.setEnabled(!isConnected);
      spinnerAddr.setEnabled(!isConnected);

      btnConnect.setEnabled(!isConnected);
      btnDisconnect.setEnabled(isConnected);
      btnCountSlots.setEnabled(isConnected);
      btnPushToFront.setEnabled(isConnected);
      btnRetract.setEnabled(isConnected);
      btnReadIccid.setEnabled(isConnected);
      btnColdReset.setEnabled(isConnected);
      btnDeactivate.setEnabled(isConnected);
      btnQueryStatus.setEnabled(isConnected);
      btnQuerySensors.setEnabled(isConnected);
      btnFirmware.setEnabled(isConnected);
      if (tvConnInfo != null) {
        tvConnInfo.setText(isConnected ? portName + " @ " + baud : "Not connected");
        tvConnInfo.setTextColor(isConnected ? 0xFF8EC97A : 0xFFCF6679);
      }
    });
  }

  private void runInThread(Runnable r) {
    new Thread(r).start();
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Connection management
  // ─────────────────────────────────────────────────────────────────────

  private void doConnect() {
    // Read current spinner selections
    portName = "/dev/" + spinnerPort.getSelectedItem().toString();
    baud = Integer.parseInt(spinnerBaud.getSelectedItem().toString());
    addr = spinnerAddr.getSelectedItem().toString();

    log("── Connect (" + portName + " @ " + baud + ") ──────────");
    crt.Select_Dev(9);  // select CRT-591M device type (same as demo)
    int fd = crt.R_Open(portName, baud);
    if (fd > 0) {
      n_Fd = fd;
      log("R_Open OK  fd=" + fd);
      setConnected(true);
      runOnUiThread(() -> tvStatus.setText("Connected"));
      toast("Connected on " + portName);
    } else {
      log("R_Open FAILED  ret=" + fd);
      log("Check: port exists? permissions? baud correct?");
      toast("Connect failed (ret=" + fd + ")");
    }
  }

  private void doDisconnect() {
    log("── Disconnect ────────────────────────────────");
    if (cardInIcPos) {
      execCmd(addr + "435131");  // deactivate contacts first
      cardInIcPos = false;
    }
    int ret = crt.R_Close(n_Fd);
    log("R_Close ret=" + ret);
    n_Fd = 0;
    setConnected(false);
    runOnUiThread(() -> {
      tvStatus.setText("Disconnected");
      tvIccid.setText("—");
    });
    toast("Disconnected");
  }

  @Override
  protected void onDestroy() {
    if (connected && n_Fd > 0) {
      crt.R_Close(n_Fd);
    }
    super.onDestroy();
  }

  // ─────────────────────────────────────────────────────────────────────
  //  SIM kiosk operations
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Count how many SIM cards are sitting in the holder / stacker.
   * <p>
   * Strategy: query sensors. The response byte layout for command 433131: byte[6]  = sensor group 1
   * (S1..S8 bit-flags) byte[7]  = sensor group 2 (S9..S16 bit-flags)  [if present]
   * <p>
   * Sensor S1 (bit 0 of byte[6]) = card-present at IC position. Sensor S2 (bit 1 of byte[6]) =
   * card-present at entry gate. Sensor S3 (bit 2 of byte[6]) = card-present at exit gate.
   * <p>
   * For a single-lane reader the "count" is 1 if any sensor fires, 0 otherwise.  For a stacker the
   * firmware reports the count in a dedicated status byte — we read byte[8] as "stacker count"
   * (0xFF if not a stacker model, in which case we fall back to presence detection).
   */
  private void doCountSlots() {
    log("── Count SIMs in holder ──────────────────");

    CommandResult cr = execCmd(addr + "433131");   // query sensors
    if (!cr.ok) {
      log("Sensor query failed.\n");
      runOnUiThread(() -> tvSlotCount.setText("? (error)"));
      return;
    }

    int sensorGroup1 = cr.out[6] & 0xFF;
    int sensorGroup2 = (cr.outLen > 7) ? (cr.out[7] & 0xFF) : 0;
    int stackerCount = (cr.outLen > 8) ? (cr.out[8] & 0xFF) : 0xFF;

    log(String.format("Sensor G1=0x%02X  G2=0x%02X  stackerByte=0x%02X", sensorGroup1, sensorGroup2,
        stackerCount));

    boolean s1CardAtIc = (sensorGroup1 & 0x01) != 0;  // IC position
    boolean s2CardAtEntry = (sensorGroup1 & 0x02) != 0;  // entry gate
    boolean s3CardAtExit = (sensorGroup1 & 0x04) != 0;  // exit gate
    boolean s4CardInPath = (sensorGroup1 & 0x08) != 0;  // mid-path

    int count;
    if (stackerCount != 0xFF) {
      // Stacker model: firmware reports real count
      count = stackerCount;
    } else {
      // Single-lane: 1 if any sensor detects card presence
      count = (s1CardAtIc || s2CardAtEntry || s3CardAtExit || s4CardInPath) ? 1 : 0;
    }

    log("Sensor flags — IC:" + s1CardAtIc + "  Entry:" + s2CardAtEntry + "  Exit:" + s3CardAtExit
        + "  Path:" + s4CardInPath);
    log("Detected card count: " + count);

    int finalCount = count;
    runOnUiThread(() -> tvSlotCount.setText(String.valueOf(finalCount)));
  }

  /**
   * Push the SIM card to the front (exit gate) so the user can pick it up. Sequence: 1. Move to
   * no-card-hold (release clamp)     433239 2. Move card to exit gate                   433030
   * (reset + eject) 3. Enable exit-gate for user pickup         433240
   */
  private void doPushToFront() {
    log("── Push SIM to exit gate ─────────────────");

    // Step 1 – release hold position
    CommandResult cr = execCmd(addr + "433239");
    log("Release hold: " + (cr.ok ? "OK" : "FAIL"));

    // Step 2 – move card to exit gate (eject)
    cr = execCmd(addr + "433030");
    log("Eject to gate: " + (cr.ok ? "OK" : "FAIL"));
    if (!cr.ok) {
      toast("Eject failed");
      return;
    }

    // Step 3 – signal device to wait for user pickup
    cr = execCmd(addr + "433240");
    log("Wait for pickup: " + (cr.ok ? "OK" : "FAIL"));

    toast(cr.ok ? "Card at exit gate — please take your SIM" : "Push failed");
    runOnUiThread(() -> tvStatus.setText("Card at exit gate"));
  }

  /**
   * Retract / return the card from wherever it is back to the recycle bin. Sequence: 1. If card
   * contacts are powered, deactivate first   435131 2. Move to recycle bin 433233 OR reset +
   * recycle                              433031
   */
  private void doRetractCard() {
    log("── Retract / recycle SIM ─────────────────");

    // Deactivate contacts if active (safe to call even if not active)
    if (cardInIcPos) {
      CommandResult deact = execCmd(addr + "435131");
      log("Deactivate contacts: " + (deact.ok ? "OK" : "FAIL (ignored)"));
      cardInIcPos = false;
    }

    // Move to recycle bin
    CommandResult cr = execCmd(addr + "433031");  // reset + recycle
    log("Move to recycle: " + (cr.ok ? "OK" : "FAIL"));

    toast(cr.ok ? "Card retracted to recycle bin" : "Retract failed");
    runOnUiThread(() -> {
      tvStatus.setText("Card retracted");
      tvIccid.setText("—");
    });
  }

  /**
   * Move card to IC position → cold-reset → read ICCID via APDU sequence.
   * <p>
   * ICCID is stored in EF_ICCID at MF (3F00) / EF 2FE2. It is 10 bytes BCD-encoded, swap nibbles to
   * get the digit string.
   */
  private void doReadIccid() {
    log("── Read ICCID ────────────────────────────");

    // 1. Activate card (cold-reset at 3V)
    if (!activateCard()) {
      toast("Cannot activate card");
      return;
    }

    // 2. SELECT EF_ICCID  (00 A4 00 00 02 2F E2)
    log("SELECT EF_ICCID...");
    String selectCmd = buildApdu("00A4000002" + "2FE2");
    CommandResult cr = execCmd(addr + selectCmd);
    log("SELECT: " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));

    if (!cr.ok) {
      // Some SIMs require SELECT by DF path first
      log("Retry via DF path (3F00 / 2FE2)...");
      String sel3f00 = buildApdu("00A4000002" + "3F00");
      execCmd(addr + sel3f00);

      cr = execCmd(addr + selectCmd);
      log("SELECT (retry): " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));
      if (!cr.ok) {
        toast("SELECT EF_ICCID failed");
        return;
      }
    }

    // 3. READ BINARY  (00 B0 00 00 0A — read 10 bytes)
    log("READ BINARY (10 bytes)...");
    String readCmd = buildApdu("00B000000A");
    cr = execCmd(addr + readCmd);
    log("READ: " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));

    if (!cr.ok) {
      toast("READ BINARY failed");
      return;
    }

    // Response layout from ExeCommand: byte[6..15] = 10 BCD bytes
    if (cr.outLen < 16) {
      log("Response too short: " + cr.outLen);
      toast("Unexpected response length");
      return;
    }

    byte[] bcd = Arrays.copyOfRange(cr.out, 6, 16); // 10 bytes
    String iccid = bcdToIccid(bcd);
    log("Raw BCD: " + Utils.bytes2HexStr(bcd, bcd.length, true));
    log("ICCID:   " + iccid);

    runOnUiThread(() -> {
      tvIccid.setText(iccid);
      tvStatus.setText("ICCID read OK");
    });
    toast("ICCID: " + iccid);
  }

  /**
   * Cold-reset the card at 3V and log the ATR.
   */
  private void doColdReset() {
    log("── Cold Reset (3V) ───────────────────────");
    activateCard();
  }

  /**
   * Deactivate (power off) the card contacts.
   */
  private void doDeactivate() {
    log("── Deactivate contacts ───────────────────");
    CommandResult cr = execCmd(addr + "435131");
    log("Deactivate: " + (cr.ok ? "OK" : "FAIL"));
    if (cr.ok) {
      cardInIcPos = false;
      runOnUiThread(() -> tvStatus.setText("Deactivated"));
    }
    toast(cr.ok ? "Contacts deactivated" : "Deactivate failed");
  }

  /**
   * Query device status — returns status byte at out[6].
   * <p>
   * Status byte bit-map (typical CRT-591M): bit7 = card present in IC position bit6 = card present
   * at entry gate bit5 = card jam detected bit4 = recycle box full bit0 = device ready
   */
  private void doQueryStatus() {
    log("── Query Status ──────────────────────────");
    CommandResult cr = execCmd(addr + "433130");
    if (!cr.ok) {
      log("Query failed.");
      return;
    }

    int status = cr.outLen > 6 ? (cr.out[6] & 0xFF) : 0;
    boolean cardAtIc = (status & 0x80) != 0;
    boolean cardAtEntry = (status & 0x40) != 0;
    boolean cardJam = (status & 0x20) != 0;
    boolean boxFull = (status & 0x10) != 0;
    boolean ready = (status & 0x01) != 0;

    String statusStr = String.format(
        "Status=0x%02X  Ready:%b  CardAtIC:%b  CardAtEntry:%b  Jam:%b  BoxFull:%b", status, ready,
        cardAtIc, cardAtEntry, cardJam, boxFull);
    log(statusStr);
    runOnUiThread(() -> tvStatus.setText(statusStr));
  }

  /**
   * Query all sensors and dump the raw sensor bitmap.
   */
  private void doQuerySensors() {
    log("── Query Sensors ─────────────────────────");
    CommandResult cr = execCmd(addr + "433131");
    if (!cr.ok) {
      log("Sensor query failed.");
      return;
    }

    if (cr.outLen > 6) {
      StringBuilder sb = new StringBuilder("Sensors:");
      for (int i = 6; i < cr.outLen; i++) {
        sb.append(String.format(" G%d=0x%02X", i - 5, cr.out[i] & 0xFF));
      }
      log(sb.toString());
      runOnUiThread(() -> tvStatus.setText(sb.toString()));
    }
  }

  /**
   * Read firmware version string.
   */
  private void doFirmwareVersion() {
    log("── Firmware Version ──────────────────────");
    CommandResult cr = execCmd(addr + "43A430");
    if (!cr.ok) {
      log("Query failed.");
      return;
    }

    // Version string starts at byte[6]
    if (cr.outLen > 6) {
      byte[] verBytes = Arrays.copyOfRange(cr.out, 6, cr.outLen);
      String version = new String(verBytes).trim();
      log("Firmware: " + version);
      runOnUiThread(() -> tvStatus.setText("FW: " + version));
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Internal helpers
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Move card to IC position, then cold-reset at 3V.
   *
   * @return true if reset succeeded and ATR was received.
   */
  private boolean activateCard() {
    // Move to IC contact position
    log("Moving to IC position...");
    CommandResult cr = execCmd(addr + "433231");
    log("Move to IC: " + (cr.ok ? "OK" : "FAIL"));
    if (!cr.ok) {
      return false;
    }

    // Cold-reset at 3V  (VCC = "33")
    log("Cold reset at 3V...");
    cr = execCmd(addr + "435130" + "33");
    log("Cold reset: " + (cr.ok ? "OK" : "FAIL"));

    if (cr.ok && cr.outLen > 6) {
      // byte[6] = T type (0x30 → T=0, 0x31 → T=1)
      cardType = (cr.out[6] == 0x30) ? 0 : 1;
      byte[] atr = Arrays.copyOfRange(cr.out, 7, cr.outLen);
      log("T=" + cardType + "  ATR=" + Utils.bytes2HexStr(atr, atr.length, true).toUpperCase());
      cardInIcPos = true;
      runOnUiThread(() -> tvStatus.setText("Card active, T=" + cardType));
    }

    return cr.ok;
  }

  /**
   * Build the command code for sending an APDU via CRT-591M. Format: 4351 [type] [apdu_hex] type:
   * "33" = T=0,  "34" = T=1
   */
  private String buildApdu(String apduHex) {
    String typeStr = (cardType == 0) ? "33" : "34";
    return "4351" + typeStr + apduHex;
  }

  /**
   * Extract SW1SW2 from response (last 2 bytes of response data, bytes [outLen-2..outLen-1]).
   */
  private String getSW(CommandResult cr) {
    if (cr.outLen < 2) {
      return "????";
    }
    return String.format("%02X%02X", cr.out[cr.outLen - 2] & 0xFF, cr.out[cr.outLen - 1] & 0xFF);
  }

  /**
   * Decode 10-byte BCD ICCID (swap nibbles per ETSI TS 102 221). Each byte: low nibble first, high
   * nibble second. 'F' padding nibbles are stripped.
   */
  private String bcdToIccid(byte[] bcd) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bcd) {
      int lo = b & 0x0F;
      int hi = (b >> 4) & 0x0F;
      if (lo != 0x0F) {
        sb.append(lo);
      }
      if (hi != 0x0F) {
        sb.append(hi);
      }
    }
    return sb.toString();
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Low-level command executor
  // ─────────────────────────────────────────────────────────────────────

  private static class CommandResult {

    boolean ok;
    byte[] out;
    int outLen;
    int rc;
  }

  /**
   * Execute a command string (hex), return parsed result. commandStr: full hex string including
   * address byte.
   */
  private CommandResult execCmd(String commandStr) {
    CommandResult result = new CommandResult();
    result.out = new byte[3072];

    String regex = "(.{2})";
    log("→ " + commandStr.replaceAll(regex, "$1 ").toUpperCase());

    byte[] inBuf = Utils.hexStr2ByteArrs(commandStr);
    int length = commandStr.length() / 2;

    int[] outLenArr = new int[]{0, 0};

    try {
      result.rc = crt.R_ExeCommandS(n_Fd, commandStr, length, result.out, outLenArr);
    } catch (Exception e) {
      log("R_ExeCommandS FAILED!");
      e.printStackTrace();
      result.rc = crt.R_ExeCommandB(n_Fd, inBuf, length, result.out, outLenArr);
    }
    result.outLen = outLenArr[0] * 256 + outLenArr[1];
    result.ok = (result.rc == 0) && (result.outLen > 0) && (result.out[0] == 0x50);

    log("← " + Utils.bytes2HexStr(result.out, result.outLen, true).toUpperCase() + "  [rc="
        + result.rc + "  len=" + result.outLen + "]");

    return result;
  }

  private void toast(final String msg) {
    runOnUiThread(() -> Toast.makeText(SimKioskActivity.this, msg, Toast.LENGTH_LONG).show());
  }
}
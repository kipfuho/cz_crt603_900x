package com.kiosk.activity;

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
import com.kiosk.crt.Utils;
import com.kiosk.test.R;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SimKioskActivity — CRT-591M SIM dispenser controller.
 * <p>
 * ── Command reference ──────────────────────────────────────────────────────── All commands: [ADDR
 * 1B][CMD 2B][SUB 2B][DATA...] Response byte[0] == 0x50 → success
 * <p>
 * Card transport [addr]433030   Reset + eject to exit gate (card fully released) [addr]433031 Reset
 * + recycle (move card to recycle bin) [addr]433033   Reset, card stays in place [addr]433230 Move
 * to card-hold position (partially out, still retractable) [addr]433231   Move to IC-contact
 * position [addr]433232   Move to RF position [addr]433233   Move to recycle bin [addr]433239 Move
 * to no-card-hold (fully retract) [addr]433240   Wait / signal for user pickup at exit gate
 * [addr]433330   Enable card entry from exit gate [addr]433331   Disable card entry from exit gate
 * <p>
 * Status / query [addr]433130   Query device status  → byte[6] = status flags [addr]433131   Query
 * sensors        → byte[6] = sensor bitmap bit0 = card at IC position bit1 = card at entry gate
 * bit2 = card at exit gate bit3 = card in transport path bit4 = stock LOW  (few SIMs remaining)
 * bit5 = stock MED  (medium stock) bit6 = stock HIGH (plenty of SIMs) [addr]43A230   Read device
 * serial number [addr]43A330   Read device config [addr]43A430   Read firmware version
 * <p>
 * IC / SAM card [addr]435130[VCC]     Cold-reset   VCC: 30=5V  33=3V [addr]435138 Warm-reset
 * [addr]435131          Deactivate (power off contacts) [addr]4351[T][APDU]   Send APDU T: 33=T=0
 * 34=T=1
 * <p>
 * SIM stock level (from sensor byte[6] bits 4-6) LOW  : bit4 set, bits 5-6 clear MED  : bit5 set
 * HIGH : bit6 set (only one bit will be set at a time; if none set → unknown / empty)
 */
public class SimKioskActivity extends Activity {

  private static final String TAG = "SimKiosk";

  // ── SDK ───────────────────────────────────────────────────────────────
  private Crtdriver crt;

  // ── State ─────────────────────────────────────────────────────────────
  private int n_Fd = 0;
  private String addr = "00";
  private String portName = "/dev/ttyS5";
  private int baud = 115200;
  private int cardType = 0;       // 0=T=0, 1=T=1
  private boolean cardInIcPos = false;
  private boolean connected = false;

  // ── UI — TextViews ────────────────────────────────────────────────────
  private TextView tvLog;
  private TextView tvIccid;
  private TextView tvStatus;
  private TextView tvStockLevel;   // replaces tvSlotCount
  private TextView tvConnInfo;

  // ── UI — Buttons ──────────────────────────────────────────────────────
  private Button btnConnect;
  private Button btnDisconnect;

  // Stock / transport
  private Button btnCheckStock;
  private Button btnDispense;          // dispense one SIM to hold position
  private Button btnPushToGate;        // present at exit gate (retractable)
  private Button btnEjectFull;         // full eject — user takes card
  private Button btnRetract;           // retract back to no-hold
  private Button btnRecycle;           // send to recycle bin
  private Button btnEnableEntry;       // allow card entry from gate
  private Button btnDisableEntry;      // block card entry from gate

  // IC / SIM chip
  private Button btnReadIccid;
  private Button btnColdReset;
  private Button btnWarmReset;
  private Button btnDeactivate;

  // Device queries
  private Button btnQueryStatus;
  private Button btnQuerySensors;
  private Button btnSerialNumber;
  private Button btnDeviceConfig;
  private Button btnFirmware;

  // Misc
  private Button btnClearLog;

  // ── UI — Spinners ─────────────────────────────────────────────────────
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

    // Grant tty permissions
    try {
      Process process = Runtime.getRuntime().exec("su");
      DataOutputStream os = new DataOutputStream(process.getOutputStream());
      os.writeBytes("chown root:root /dev/tty* \n");
      os.writeBytes("chmod 777 /dev/tty* \n");
      os.writeBytes("exit\n");
      os.flush();
      os.close();
      process.waitFor();
    } catch (IOException | InterruptedException e) {
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

  @Override
  protected void onDestroy() {
    if (connected && n_Fd > 0) {
      crt.R_Close(n_Fd);
    }
    super.onDestroy();
  }

  // ─────────────────────────────────────────────────────────────────────
  //  View binding
  // ─────────────────────────────────────────────────────────────────────

  private void bindViews() {
    tvLog = findViewById(R.id.tvSimLog);
    tvIccid = findViewById(R.id.tvIccid);
    tvStatus = findViewById(R.id.tvSimStatus);
    tvStockLevel = findViewById(R.id.tvStockLevel);
    tvConnInfo = findViewById(R.id.tvConnInfo);

    btnConnect = findViewById(R.id.btnSimConnect);
    btnDisconnect = findViewById(R.id.btnSimDisconnect);

    btnCheckStock = findViewById(R.id.btnCheckStock);
    btnDispense = findViewById(R.id.btnDispense);
    btnPushToGate = findViewById(R.id.btnPushToGate);
    btnEjectFull = findViewById(R.id.btnEjectFull);
    btnRetract = findViewById(R.id.btnRetract);
    btnRecycle = findViewById(R.id.btnRecycle);
    btnEnableEntry = findViewById(R.id.btnEnableEntry);
    btnDisableEntry = findViewById(R.id.btnDisableEntry);

    btnReadIccid = findViewById(R.id.btnReadIccid);
    btnColdReset = findViewById(R.id.btnSimColdReset);
    btnWarmReset = findViewById(R.id.btnSimWarmReset);
    btnDeactivate = findViewById(R.id.btnSimDeactivate);

    btnQueryStatus = findViewById(R.id.btnSimQueryStatus);
    btnQuerySensors = findViewById(R.id.btnSimQuerySensors);
    btnSerialNumber = findViewById(R.id.btnSimSerialNumber);
    btnDeviceConfig = findViewById(R.id.btnSimDeviceConfig);
    btnFirmware = findViewById(R.id.btnSimFirmware);

    btnClearLog = findViewById(R.id.btnSimClearLog);

    spinnerPort = findViewById(R.id.spinnerSimPort);
    spinnerBaud = findViewById(R.id.spinnerSimBaud);
    spinnerAddr = findViewById(R.id.spinnerSimAddr);

    // ── Populate spinners ─────────────────────────────────────────────
    String[] ports = getSerialPortNames();
    spinnerPort.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, ports));
    String intentPort = portName.replace("/dev/", "");
    for (int i = 0; i < ports.length; i++) {
      if (ports[i].equals(intentPort)) {
        spinnerPort.setSelection(i);
        break;
      }
    }

    String[] bauds = {"9600", "19200", "38400", "57600", "115200"};
    spinnerBaud.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, bauds));
    String intentBaud = String.valueOf(baud);
    for (int i = 0; i < bauds.length; i++) {
      if (bauds[i].equals(intentBaud)) {
        spinnerBaud.setSelection(i);
        break;
      }
    }

    String[] addrs = {"00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "0A", "0B", "0C",
        "0D", "0E", "0F"};
    spinnerAddr.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, addrs));
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
      names.add("ttyS5");
    }
    return names.toArray(new String[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Handler
  // ─────────────────────────────────────────────────────────────────────

  private void setupHandler() {
    mHandler = new Handler(msg -> {
      if (msg.obj instanceof String) {
        tvLog.append((String) msg.obj);
        int offset = tvLog.getLineCount() * tvLog.getLineHeight();
        if (offset > tvLog.getHeight()) {
          tvLog.scrollTo(0, offset - tvLog.getHeight());
        }
      }
      return true;
    });
  }

  private void log(String s) {
    Log.i(TAG, s);
    Message msg = Message.obtain(mHandler);
    msg.obj = s + "\n";
    mHandler.sendMessage(msg);
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Listeners
  // ─────────────────────────────────────────────────────────────────────

  private void setupListeners() {
    btnConnect.setOnClickListener(v -> runInThread(this::doConnect));
    btnDisconnect.setOnClickListener(v -> runInThread(this::doDisconnect));

    btnCheckStock.setOnClickListener(v -> runInThread(this::doCheckStock));
    btnDispense.setOnClickListener(v -> runInThread(this::doDispense));
    btnPushToGate.setOnClickListener(v -> runInThread(this::doPushToGate));
    btnEjectFull.setOnClickListener(v -> runInThread(this::doEjectFull));
    btnRetract.setOnClickListener(v -> runInThread(this::doRetract));
    btnRecycle.setOnClickListener(v -> runInThread(this::doRecycle));
    btnEnableEntry.setOnClickListener(v -> runInThread(this::doEnableEntry));
    btnDisableEntry.setOnClickListener(v -> runInThread(this::doDisableEntry));

    btnReadIccid.setOnClickListener(v -> runInThread(this::doReadIccid));
    btnColdReset.setOnClickListener(v -> runInThread(this::doColdReset));
    btnWarmReset.setOnClickListener(v -> runInThread(this::doWarmReset));
    btnDeactivate.setOnClickListener(v -> runInThread(this::doDeactivate));

    btnQueryStatus.setOnClickListener(v -> runInThread(this::doQueryStatus));
    btnQuerySensors.setOnClickListener(v -> runInThread(this::doQuerySensors));
    btnSerialNumber.setOnClickListener(v -> runInThread(this::doSerialNumber));
    btnDeviceConfig.setOnClickListener(v -> runInThread(this::doDeviceConfig));
    btnFirmware.setOnClickListener(v -> runInThread(this::doFirmwareVersion));

    btnClearLog.setOnClickListener(v -> runOnUiThread(() -> tvLog.setText("")));
  }

  private void runInThread(Runnable r) {
    new Thread(r).start();
  }

  private void setConnected(boolean isConnected) {
    connected = isConnected;
    runOnUiThread(() -> {
      spinnerPort.setEnabled(!isConnected);
      spinnerBaud.setEnabled(!isConnected);
      spinnerAddr.setEnabled(!isConnected);

      btnConnect.setEnabled(!isConnected);
      btnDisconnect.setEnabled(isConnected);

      btnCheckStock.setEnabled(isConnected);
      btnDispense.setEnabled(isConnected);
      btnPushToGate.setEnabled(isConnected);
      btnEjectFull.setEnabled(isConnected);
      btnRetract.setEnabled(isConnected);
      btnRecycle.setEnabled(isConnected);
      btnEnableEntry.setEnabled(isConnected);
      btnDisableEntry.setEnabled(isConnected);

      btnReadIccid.setEnabled(isConnected);
      btnColdReset.setEnabled(isConnected);
      btnWarmReset.setEnabled(isConnected);
      btnDeactivate.setEnabled(isConnected);

      btnQueryStatus.setEnabled(isConnected);
      btnQuerySensors.setEnabled(isConnected);
      btnSerialNumber.setEnabled(isConnected);
      btnDeviceConfig.setEnabled(isConnected);
      btnFirmware.setEnabled(isConnected);

      if (tvConnInfo != null) {
        tvConnInfo.setText(
            isConnected ? portName + " @ " + baud + "  [" + addr + "]" : "Not connected");
        tvConnInfo.setTextColor(isConnected ? 0xFF8EC97A : 0xFFCF6679);
      }
    });
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Connection
  // ─────────────────────────────────────────────────────────────────────

  private void doConnect() {
    portName = "/dev/" + spinnerPort.getSelectedItem().toString();
    baud = Integer.parseInt(spinnerBaud.getSelectedItem().toString());
    addr = spinnerAddr.getSelectedItem().toString();

    log("── Connect (" + portName + " @ " + baud + "  addr=" + addr + ") ──");
    crt.Select_Dev(9);
    int fd = crt.R_Open(portName, baud);
    if (fd > 0) {
      n_Fd = fd;
      log("R_Open OK  fd=" + fd);
      setConnected(true);
      runOnUiThread(() -> tvStatus.setText("Connected"));
      toast("Connected on " + portName);
    } else {
      log("R_Open FAILED  ret=" + fd);
      toast("Connect failed (ret=" + fd + ")");
    }
  }

  private void doDisconnect() {
    log("── Disconnect ──────────────────────────────────");
    if (cardInIcPos) {
      execCmd(addr + "435131");   // deactivate contacts first
      cardInIcPos = false;
    }
    int ret = crt.R_Close(n_Fd);
    log("R_Close ret=" + ret);
    n_Fd = 0;
    setConnected(false);
    runOnUiThread(() -> {
      tvStatus.setText("Disconnected");
      tvIccid.setText("—");
      tvStockLevel.setText("—");
    });
    toast("Disconnected");
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Stock management
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Check SIM stock level via sensor bitmap.
   * <p>
   * Sensor byte[6] stock bits: bit4 = LOW  (almost empty — refill soon) bit5 = MED  (medium stock)
   * bit6 = HIGH (well stocked) Only one bit should be set at a time. If none set → dispenser empty
   * or sensor fault.
   */
  private void doCheckStock() {
    log("── Check Stock Level ───────────────────────────");
    CommandResult cr = execCmd(addr + "433131");
    if (!cr.ok) {
      log("Sensor query failed.");
      runOnUiThread(() -> tvStockLevel.setText("ERROR"));
      return;
    }

    int sensors = cr.outLen > 6 ? (cr.out[6] & 0xFF) : 0;
    log(String.format("Sensor byte=0x%02X", sensors));

    // Card position flags (bits 0-3)
    boolean cardAtIc = (sensors & 0x01) != 0;
    boolean cardAtEntry = (sensors & 0x02) != 0;
    boolean cardAtExit = (sensors & 0x04) != 0;
    boolean cardInPath = (sensors & 0x08) != 0;

    // Stock level flags (bits 4-6)
    boolean stockLow = (sensors & 0x10) != 0;
    boolean stockMed = (sensors & 0x20) != 0;
    boolean stockHigh = (sensors & 0x40) != 0;

    String level;
    int levelColor;
    if (stockHigh) {
      level = "HIGH";
      levelColor = 0xFF8EC97A;  // green
    } else if (stockMed) {
      level = "MED";
      levelColor = 0xFFF0A500;  // amber
    } else if (stockLow) {
      level = "LOW";
      levelColor = 0xFFCF6679;  // red
    } else {
      level = "EMPTY";
      levelColor = 0xFFCF6679;
    }

    log("Stock level: " + level);
    log("Card pos — IC:" + cardAtIc + "  Entry:" + cardAtEntry + "  Exit:" + cardAtExit + "  Path:"
        + cardInPath);

    int finalColor = levelColor;
    String finalLevel = level;
    runOnUiThread(() -> {
      tvStockLevel.setText(finalLevel);
      tvStockLevel.setTextColor(finalColor);
    });
  }

  /**
   * Dispense one SIM: move from stacker to card-hold position. Card is held by the transport —
   * still retractable at this point. Call doPushToGate() or doEjectFull() after this to present to
   * user.
   */
  private void doDispense() {
    log("── Dispense SIM ────────────────────────────────");

    // Step 1 — move to card-hold (partially out, retractable)
    CommandResult cr = execCmd(addr + "433230");
    log("Move to hold: " + (cr.ok ? "OK" : "FAIL"));
    if (!cr.ok) {
      toast("Dispense failed");
      return;
    }

    log("SIM dispensed to hold position. Ready to push or retract.");
    runOnUiThread(() -> tvStatus.setText("SIM at hold — push or retract"));
    toast("SIM dispensed to hold position");
  }

  /**
   * Push SIM to exit gate — card is presented but transport still holds the tail. Can still be
   * retracted with doRetract().
   */
  private void doPushToGate() {
    log("── Push SIM to gate (retractable) ──────────────");

    // Move to card-hold at gate (not full eject)
    CommandResult cr = execCmd(addr + "433230");
    log("Push to gate: " + (cr.ok ? "OK" : "FAIL"));
    if (!cr.ok) {
      toast("Push failed");
      return;
    }

    // Signal device: waiting for user to take card
    cr = execCmd(addr + "433240");
    log("Wait-for-pickup signal: " + (cr.ok ? "OK" : "FAIL (non-fatal)"));

    runOnUiThread(() -> tvStatus.setText("SIM at gate — retractable"));
    toast("SIM presented at gate. Can still retract.");
  }

  /**
   * Full eject — drives card completely out past the gate into user's hand. Card CANNOT be
   * retracted after this.
   */
  private void doEjectFull() {
    log("── Full Eject (card released) ───────────────────");

    CommandResult cr = execCmd(addr + "433030");  // reset + full eject
    log("Full eject: " + (cr.ok ? "OK" : "FAIL"));
    if (!cr.ok) {
      toast("Eject failed");
      return;
    }

    cardInIcPos = false;
    runOnUiThread(() -> {
      tvStatus.setText("Card ejected — taken by user");
      tvIccid.setText("—");
    });
    toast("Card fully ejected. Cannot retract.");
  }

  /**
   * Retract the card from gate / hold back to no-hold position inside the machine.
   */
  private void doRetract() {
    log("── Retract SIM ─────────────────────────────────");

    if (cardInIcPos) {
      CommandResult deact = execCmd(addr + "435131");
      log("Deactivate contacts: " + (deact.ok ? "OK" : "FAIL (ignored)"));
      cardInIcPos = false;
    }

    // Move to no-card-hold (fully retract)
    CommandResult cr = execCmd(addr + "433239");
    log("Retract to no-hold: " + (cr.ok ? "OK" : "FAIL"));

    toast(cr.ok ? "SIM retracted" : "Retract failed");
    runOnUiThread(() -> tvStatus.setText(cr.ok ? "SIM retracted" : "Retract failed"));
  }

  /**
   * Send the card to the recycle bin.
   */
  private void doRecycle() {
    log("── Recycle SIM ─────────────────────────────────");

    if (cardInIcPos) {
      execCmd(addr + "435131");
      cardInIcPos = false;
    }

    // reset + recycle
    CommandResult cr = execCmd(addr + "433031");
    log("Recycle: " + (cr.ok ? "OK" : "FAIL"));

    toast(cr.ok ? "SIM sent to recycle bin" : "Recycle failed");
    runOnUiThread(() -> {
      tvStatus.setText(cr.ok ? "SIM recycled" : "Recycle failed");
      tvIccid.setText("—");
    });
  }

  /**
   * Enable card entry from exit gate (user can insert a card).
   */
  private void doEnableEntry() {
    log("── Enable Card Entry ───────────────────────────");
    CommandResult cr = execCmd(addr + "433330");
    log("Enable entry: " + (cr.ok ? "OK" : "FAIL"));
    runOnUiThread(() -> tvStatus.setText(cr.ok ? "Entry enabled" : "Enable entry failed"));
    toast(cr.ok ? "Card entry enabled" : "Failed");
  }

  /**
   * Disable card entry from exit gate.
   */
  private void doDisableEntry() {
    log("── Disable Card Entry ──────────────────────────");
    CommandResult cr = execCmd(addr + "433331");
    log("Disable entry: " + (cr.ok ? "OK" : "FAIL"));
    runOnUiThread(() -> tvStatus.setText(cr.ok ? "Entry disabled" : "Disable entry failed"));
    toast(cr.ok ? "Card entry disabled" : "Failed");
  }

  // ─────────────────────────────────────────────────────────────────────
  //  IC / SIM chip operations
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Read ICCID: activate card → SELECT EF_ICCID → READ BINARY.
   */
  private void doReadIccid() {
    log("── Read ICCID ──────────────────────────────────");

    if (!activateCard()) {
      toast("Cannot activate card");
      return;
    }

    // SELECT EF_ICCID (MF level: 2FE2)
    log("SELECT EF_ICCID...");
    String selectCmd = buildApdu("00A4000002" + "2FE2");
    CommandResult cr = execCmd(addr + selectCmd);
    log("SELECT: " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));

    if (!cr.ok) {
      // Retry via full MF path: 3F00 → 2FE2
      log("Retry via MF path...");
      execCmd(addr + buildApdu("00A4000002" + "3F00"));
      cr = execCmd(addr + selectCmd);
      log("SELECT (retry): " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));
      if (!cr.ok) {
        toast("SELECT EF_ICCID failed");
        return;
      }
    }

    // READ BINARY — 10 bytes
    log("READ BINARY (10 bytes)...");
    String readCmd = buildApdu("00B000000A");
    cr = execCmd(addr + readCmd);
    log("READ: " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));
    if (!cr.ok) {
      toast("READ BINARY failed");
      return;
    }

    if (cr.outLen < 16) {
      log("Response too short: " + cr.outLen);
      toast("Unexpected response length");
      return;
    }

    byte[] bcd = Arrays.copyOfRange(cr.out, 6, 16);
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
   * Cold-reset at 3V and log the ATR.
   */
  private void doColdReset() {
    log("── Cold Reset (3V) ─────────────────────────────");
    activateCard();
  }

  /**
   * Warm-reset — re-initialise card without powering contacts off. Card must already be active
   * (cold-reset done first).
   */
  private void doWarmReset() {
    log("── Warm Reset ──────────────────────────────────");
    if (!cardInIcPos) {
      log("Card not active — running cold reset first.");
      if (!activateCard()) {
        toast("Cannot activate card");
        return;
      }
    }
    CommandResult cr = execCmd(addr + "435138");
    log("Warm reset: " + (cr.ok ? "OK" : "FAIL"));
    if (cr.ok && cr.outLen > 6) {
      cardType = (cr.out[6] == 0x30) ? 0 : 1;
      byte[] atr = Arrays.copyOfRange(cr.out, 7, cr.outLen);
      log("T=" + cardType + "  ATR=" + Utils.bytes2HexStr(atr, atr.length, true).toUpperCase());
      runOnUiThread(() -> tvStatus.setText("Warm reset OK, T=" + cardType));
    }
    toast(cr.ok ? "Warm reset OK" : "Warm reset failed");
  }

  /**
   * Deactivate (power off) card contacts.
   */
  private void doDeactivate() {
    log("── Deactivate ──────────────────────────────────");
    CommandResult cr = execCmd(addr + "435131");
    log("Deactivate: " + (cr.ok ? "OK" : "FAIL"));
    if (cr.ok) {
      cardInIcPos = false;
      runOnUiThread(() -> tvStatus.setText("Contacts deactivated"));
    }
    toast(cr.ok ? "Contacts deactivated" : "Deactivate failed");
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Device queries
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Query device status byte. bit7 = card at IC position bit6 = card at entry gate bit5 = card jam
   * bit4 = recycle box full bit0 = device ready
   */
  private void doQueryStatus() {
    log("── Query Status ────────────────────────────────");
    CommandResult cr = execCmd(addr + "433130");
    if (!cr.ok) {
      log("Query failed.");
      return;
    }

    int s = cr.outLen > 6 ? (cr.out[6] & 0xFF) : 0;
    String str = String.format(
        "Status=0x%02X  Ready:%b  CardAtIC:%b  CardAtEntry:%b  Jam:%b  BoxFull:%b", s,
        (s & 0x01) != 0, (s & 0x80) != 0, (s & 0x40) != 0, (s & 0x20) != 0, (s & 0x10) != 0);
    log(str);
    runOnUiThread(() -> tvStatus.setText(str));
  }

  /**
   * Query sensor bitmap and log all flags including stock level.
   */
  private void doQuerySensors() {
    log("── Query Sensors ───────────────────────────────");
    CommandResult cr = execCmd(addr + "433131");
    if (!cr.ok) {
      log("Sensor query failed.");
      return;
    }

    if (cr.outLen > 6) {
      int s = cr.out[6] & 0xFF;
      String str = String.format(
          "Sensors=0x%02X  IC:%b  Entry:%b  Exit:%b  Path:%b  Low:%b  Med:%b  High:%b", s,
          (s & 0x01) != 0, (s & 0x02) != 0, (s & 0x04) != 0, (s & 0x08) != 0, (s & 0x10) != 0,
          (s & 0x20) != 0, (s & 0x40) != 0);
      log(str);
      runOnUiThread(() -> tvStatus.setText(str));
    }
  }

  /**
   * Read device serial number.
   */
  private void doSerialNumber() {
    log("── Serial Number ───────────────────────────────");
    CommandResult cr = execCmd(addr + "43A230");
    if (!cr.ok) {
      log("Query failed.");
      return;
    }
    if (cr.outLen > 6) {
      String sn = new String(Arrays.copyOfRange(cr.out, 6, cr.outLen)).trim();
      log("Serial: " + sn);
      runOnUiThread(() -> tvStatus.setText("SN: " + sn));
    }
  }

  /**
   * Read device configuration info.
   */
  private void doDeviceConfig() {
    log("── Device Config ───────────────────────────────");
    CommandResult cr = execCmd(addr + "43A330");
    if (!cr.ok) {
      log("Query failed.");
      return;
    }
    if (cr.outLen > 6) {
      byte[] cfg = Arrays.copyOfRange(cr.out, 6, cr.outLen);
      String hex = Utils.bytes2HexStr(cfg, cfg.length, true).toUpperCase();
      log("Config: " + hex);
      runOnUiThread(() -> tvStatus.setText("Config: " + hex));
    }
  }

  /**
   * Read firmware version string.
   */
  private void doFirmwareVersion() {
    log("── Firmware Version ────────────────────────────");
    CommandResult cr = execCmd(addr + "43A430");
    if (!cr.ok) {
      log("Query failed.");
      return;
    }
    if (cr.outLen > 6) {
      String ver = new String(Arrays.copyOfRange(cr.out, 6, cr.outLen)).trim();
      log("Firmware: " + ver);
      runOnUiThread(() -> tvStatus.setText("FW: " + ver));
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Internal helpers
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Move card to IC position (skipping if already there) then cold-reset at 3V.
   *
   * @return true if cold-reset succeeded and ATR received.
   */
  private boolean activateCard() {
    // Check current position via sensor query
    log("Checking card position...");
    CommandResult status = execCmd(addr + "433131");
    boolean alreadyAtIc = status.ok && status.outLen > 6
        && (status.out[6] & 0x01) != 0;   // bit0 = card at IC position

    if (!alreadyAtIc) {
      log("Moving to IC position...");
      CommandResult cr = execCmd(addr + "433231");
      log("Move to IC: " + (cr.ok ? "OK" : "FAIL"));
      if (!cr.ok) {
        return false;
      }
    } else {
      log("Card already at IC position, skipping move.");
    }

    // Cold-reset at 3V
    log("Cold reset at 3V...");
    CommandResult cr = execCmd(addr + "435130" + "33");
    log("Cold reset: " + (cr.ok ? "OK" : "FAIL"));

    if (cr.ok && cr.outLen > 6) {
      cardType = (cr.out[6] == 0x30) ? 0 : 1;
      byte[] atr = Arrays.copyOfRange(cr.out, 7, cr.outLen);
      log("T=" + cardType + "  ATR=" + Utils.bytes2HexStr(atr, atr.length, true).toUpperCase());
      cardInIcPos = true;
      runOnUiThread(() -> tvStatus.setText("Card active, T=" + cardType));
    }

    return cr.ok;
  }

  /**
   * Build APDU command string: 4351 [T type] [apdu hex]
   */
  private String buildApdu(String apduHex) {
    return "4351" + (cardType == 0 ? "33" : "34") + apduHex;
  }

  /**
   * Extract SW1SW2 string from last 2 bytes of response.
   */
  private String getSW(CommandResult cr) {
    if (cr.outLen < 2) {
      return "????";
    }
    return String.format("%02X%02X", cr.out[cr.outLen - 2] & 0xFF, cr.out[cr.outLen - 1] & 0xFF);
  }

  /**
   * Decode 10-byte BCD ICCID, nibble-swapped per ETSI TS 102 221.
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
  //  Command executor
  // ─────────────────────────────────────────────────────────────────────

  private static class CommandResult {

    boolean ok;
    byte[] out;
    int outLen;
    int rc;
  }

  private CommandResult execCmd(String commandStr) {
    CommandResult result = new CommandResult();
    result.out = new byte[3072];
    log("→ " + commandStr.replaceAll("(.{2})", "$1 ").toUpperCase());

    byte[] inBuf = Utils.hexStr2ByteArrs(commandStr);
    int length = commandStr.length() / 2;
    int[] outLenArr = new int[]{0, 0};

    try {
      result.rc = crt.R_ExeCommandS(n_Fd, commandStr, length, result.out, outLenArr);
    } catch (Exception e) {
      log("R_ExeCommandS threw, falling back to R_ExeCommandB");
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
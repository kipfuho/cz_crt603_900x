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
 * ── Protocol response format (CRT-591-MRXX V1.4, §1.2) ──────────────────────
 * <p>
 * The driver strips the serial framing (STX/ADDR/LEN/ETX/BCC) and returns only the TXET payload in
 * out[]:
 * <p>
 * SUCCESS  → out[0]='P'(50H)  out[1]=CM  out[2]=PM  out[3]=st0  out[4]=st1 out[5]=st2
 * out[6..N]=DATA ERROR    → out[0]='N'(4EH)  out[1]=CM  out[2]=PM  out[3]=e1   out[4]=e0
 * <p>
 * All fields are ASCII characters.
 * <p>
 * Status codes (st0, st1, st2) — §2.1.2: st0: '0'=no card in machine  '1'=card at gate  '2'=card at
 * IC/RF position st1: '0'=no card in stacker  '1'=few cards     '2'=enough cards st2: '0'=bin not
 * full        '1'=bin full
 * <p>
 * Error codes (e1,e0) — §2.2: "A0" = empty stack (no card in stacker) "A1" = error card bin full
 * "10" = card jam "16" = card position movement error "43" = cannot move card to IC position "65" =
 * card not activated etc.
 * <p>
 * Sensor data (§3.1.2, PM=31H response): out[6..13] = S1..S8, each ASCII: '0'=no card  '1'=card
 * present S1=gate area  S2=IC/RF area  S3-S7=transport path sensors  S8=reserved
 * <p>
 * Card move commands (§3.1.3): CM=32H PM=30H  → card-hold position (gate, retractable) CM=32H
 * PM=31H  → IC card position (from stacker) CM=32H PM=32H  → RF card position CM=32H PM=33H  →
 * recycle bin CM=32H PM=39H  → move card out of gate (full eject) CM=32H PM=61H  → read-write area
 * → gate (card-hold) CM=32H PM=62H  → read-write area → IC position (stacker only) CM=32H PM=64H  →
 * read-write area → gate (no card-hold, full eject) CM=32H PM=72H  → dispensing motor backward
 * (retract from stacker exit)
 * <p>
 * Reset/Init commands (§3.1.1): CM=30H PM=30H  → reset, move card to gate CM=30H PM=31H  → reset,
 * capture card to recycle bin CM=30H PM=33H  → reset, do not move card CM=30H PM=34H  → same as 30H
 * + retract counter CM=30H PM=35H  → same as 31H + retract counter CM=30H PM=37H  → same as 33H +
 * retract counter Response DATA = firmware version string (e.g. "CRT-591-M001")
 * <p>
 * CPU card commands (§3.3): CM=51H PM=30H Vcc → cold reset  Vcc: 30H=5V EMV  33H=5V ISO  35H=3V ISO
 * CM=51H PM=31H     → power off / deactivate CM=51H PM=32H     → status check  DATA=sti CM=51H
 * PM=33H APDU→ T=0 APDU exchange CM=51H PM=34H APDU→ T=1 APDU exchange CM=51H PM=38H     → warm
 * reset CM=51H PM=39H APDU→ auto T=0/T=1 APDU exchange Cold-reset positive response DATA: Type(1) +
 * ATR(N) Type: '0'=T=0  '1'=T=1
 * <p>
 * Card entry (§3.1.5): CM=33H PM=30H → enable card entry from gate CM=33H PM=31H → disable card
 * entry from gate
 * <p>
 * Auto IC type check (§3.2.1): CM=50H PM=30H → returns Card_type (2 bytes ASCII)
 * <p>
 * Firmware version (§3.7): CM=DCH PM=31H → returns version string
 * <p>
 * Serial number (§3.6.8): CM=A2H PM=30H → returns len(1) + SN(N)
 * <p>
 * Machine config (§3.6.9): CM=A3H PM=30H → returns config bytes
 * <p>
 * Recycle bin counter (§3.6.10): CM=A5H PM=30H → read counter (3 ASCII digits "000"-"999") CM=A5H
 * PM=31H Count(3) → set counter initial value
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
  private int cardType = 0;       // 0=T=0, 1=T=1 (set after cold-reset)
  private boolean cardInIcPos = false;   // true after successful cold-reset
  private boolean connected = false;

  // ── UI — TextViews ────────────────────────────────────────────────────
  private TextView tvLog;
  private TextView tvIccid;
  private TextView tvStatus;
  private TextView tvStockLevel;
  private TextView tvConnInfo;

  // ── UI — Buttons ──────────────────────────────────────────────────────
  private Button btnConnect;
  private Button btnDisconnect;

  // Dispenser / transport
  private Button btnCheckStock;
  private Button btnDispense;
  private Button btnPushToGate;
  private Button btnEjectFull;
  private Button btnRetract;
  private Button btnRecycle;
  private Button btnMotorBackward;
  private Button btnEnableEntry;
  private Button btnDisableEntry;

  // IC / SIM chip
  private Button btnReadIccid;
  private Button btnAutoDetectType;
  private Button btnColdReset;
  private Button btnWarmReset;
  private Button btnDeactivate;

  // Reset / init
  private Button btnResetEject;
  private Button btnResetRecycle;
  private Button btnResetStay;

  // Device queries
  private Button btnQueryStatus;
  private Button btnQuerySensors;
  private Button btnSerialNumber;
  private Button btnDeviceConfig;
  private Button btnFirmware;
  private Button btnRecycleBinCount;

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
    Log.i(TAG, "SDK version: " + crt.getVersion());

    bindViews();
    setupHandler();
    setupListeners();
    setConnected(false);

    log("SimKiosk ready. Port=" + portName + " Baud=" + baud + " Addr=" + addr);
    log("Press CONNECT to open port.");
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
    btnMotorBackward = findViewById(R.id.btnMotorBackward);
    btnEnableEntry = findViewById(R.id.btnEnableEntry);
    btnDisableEntry = findViewById(R.id.btnDisableEntry);

    btnReadIccid = findViewById(R.id.btnReadIccid);
    btnAutoDetectType = findViewById(R.id.btnAutoDetectType);
    btnColdReset = findViewById(R.id.btnSimColdReset);
    btnWarmReset = findViewById(R.id.btnSimWarmReset);
    btnDeactivate = findViewById(R.id.btnSimDeactivate);

    btnResetEject = findViewById(R.id.btnResetEject);
    btnResetRecycle = findViewById(R.id.btnResetRecycle);
    btnResetStay = findViewById(R.id.btnResetStay);

    btnQueryStatus = findViewById(R.id.btnSimQueryStatus);
    btnQuerySensors = findViewById(R.id.btnSimQuerySensors);
    btnSerialNumber = findViewById(R.id.btnSimSerialNumber);
    btnDeviceConfig = findViewById(R.id.btnSimDeviceConfig);
    btnFirmware = findViewById(R.id.btnSimFirmware);
    btnRecycleBinCount = findViewById(R.id.btnRecycleBinCount);

    btnClearLog = findViewById(R.id.btnSimClearLog);

    spinnerPort = findViewById(R.id.spinnerSimPort);
    spinnerBaud = findViewById(R.id.spinnerSimBaud);
    spinnerAddr = findViewById(R.id.spinnerSimAddr);

    String[] ports = getSerialPortNames();
    spinnerPort.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, ports));
    String curPort = portName.replace("/dev/", "");
    for (int i = 0; i < ports.length; i++) {
      if (ports[i].equals(curPort)) {
        spinnerPort.setSelection(i);
        break;
      }
    }

    String[] bauds = {"9600", "19200", "38400", "57600", "115200"};
    spinnerBaud.setAdapter(new ArrayAdapter<>(this, R.layout.spinner_item, bauds));
    String curBaud = String.valueOf(baud);
    for (int i = 0; i < bauds.length; i++) {
      if (bauds[i].equals(curBaud)) {
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
  //  Listeners + connection state
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
    btnMotorBackward.setOnClickListener(v -> runInThread(this::doMotorBackward));
    btnEnableEntry.setOnClickListener(v -> runInThread(this::doEnableEntry));
    btnDisableEntry.setOnClickListener(v -> runInThread(this::doDisableEntry));

    btnReadIccid.setOnClickListener(v -> runInThread(this::doReadIccid));
    btnAutoDetectType.setOnClickListener(v -> runInThread(this::doAutoDetectType));
    btnColdReset.setOnClickListener(v -> runInThread(this::doColdReset));
    btnWarmReset.setOnClickListener(v -> runInThread(this::doWarmReset));
    btnDeactivate.setOnClickListener(v -> runInThread(this::doDeactivate));

    btnResetEject.setOnClickListener(v -> runInThread(() -> doReset("30")));
    btnResetRecycle.setOnClickListener(v -> runInThread(() -> doReset("31")));
    btnResetStay.setOnClickListener(v -> runInThread(() -> doReset("33")));

    btnQueryStatus.setOnClickListener(v -> runInThread(this::doQueryStatus));
    btnQuerySensors.setOnClickListener(v -> runInThread(this::doQuerySensors));
    btnSerialNumber.setOnClickListener(v -> runInThread(this::doSerialNumber));
    btnDeviceConfig.setOnClickListener(v -> runInThread(this::doDeviceConfig));
    btnFirmware.setOnClickListener(v -> runInThread(this::doFirmwareVersion));
    btnRecycleBinCount.setOnClickListener(v -> runInThread(this::doRecycleBinCount));

    btnClearLog.setOnClickListener(v -> runOnUiThread(() -> tvLog.setText("")));
  }

  private void runInThread(Runnable r) {
    new Thread(r).start();
  }

  private void setConnected(boolean on) {
    connected = on;
    runOnUiThread(() -> {
      spinnerPort.setEnabled(!on);
      spinnerBaud.setEnabled(!on);
      spinnerAddr.setEnabled(!on);
      btnConnect.setEnabled(!on);
      btnDisconnect.setEnabled(on);

      btnCheckStock.setEnabled(on);
      btnDispense.setEnabled(on);
      btnPushToGate.setEnabled(on);
      btnEjectFull.setEnabled(on);
      btnRetract.setEnabled(on);
      btnRecycle.setEnabled(on);
      btnMotorBackward.setEnabled(on);
      btnEnableEntry.setEnabled(on);
      btnDisableEntry.setEnabled(on);

      btnReadIccid.setEnabled(on);
      btnAutoDetectType.setEnabled(on);
      btnColdReset.setEnabled(on);
      btnWarmReset.setEnabled(on);
      btnDeactivate.setEnabled(on);

      btnResetEject.setEnabled(on);
      btnResetRecycle.setEnabled(on);
      btnResetStay.setEnabled(on);

      btnQueryStatus.setEnabled(on);
      btnQuerySensors.setEnabled(on);
      btnSerialNumber.setEnabled(on);
      btnDeviceConfig.setEnabled(on);
      btnFirmware.setEnabled(on);
      btnRecycleBinCount.setEnabled(on);

      tvConnInfo.setText(on ? portName + " @ " + baud + "  [" + addr + "]" : "Not connected");
      tvConnInfo.setTextColor(on ? 0xFF8EC97A : 0xFFCF6679);
    });
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Connection
  // ─────────────────────────────────────────────────────────────────────

  private void doConnect() {
    portName = "/dev/" + spinnerPort.getSelectedItem().toString();
    baud = Integer.parseInt(spinnerBaud.getSelectedItem().toString());
    addr = spinnerAddr.getSelectedItem().toString();

    log("── Connect " + portName + " @ " + baud + " addr=" + addr);
    crt.Select_Dev(9);
    int fd = crt.R_Open(portName, baud);
    if (fd > 0) {
      n_Fd = fd;
      log("R_Open OK  fd=" + fd);
      setConnected(true);
      runOnUiThread(() -> tvStatus.setText("Connected"));
      toast("Connected on " + portName);
    } else {
      log("R_Open FAILED ret=" + fd);
      toast("Connect failed (ret=" + fd + ")");
    }
  }

  private void doDisconnect() {
    log("── Disconnect");
    if (cardInIcPos) {
      execCmd(addr + "4351" + "31");   // CPU card power off
      cardInIcPos = false;
    }
    crt.R_Close(n_Fd);
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
  //  Reset / Initialization  (§3.1.1)
  //
  //  CM=30H  PM=30H  move card to gate
  //  CM=30H  PM=31H  capture card to recycle bin
  //  CM=30H  PM=33H  do not move card
  //  Response DATA = firmware version string
  // ─────────────────────────────────────────────────────────────────────

  private void doReset(String pm) {
    String label = pm.equals("30") ? "EJECT" : pm.equals("31") ? "RECYCLE" : "STAY";
    log("── Reset (" + label + ")");
    CommandResult cr = execCmd(addr + "43" + "30" + pm);
    if (cr.ok && cr.dataLen > 0) {
      String fw = asciiData(cr, 0, cr.dataLen);
      log("FW version: " + fw);
      runOnUiThread(() -> tvStatus.setText("Reset OK  FW=" + fw));
    } else {
      logError("Reset", cr);
    }
    // Reset clears cardInIcPos state
    cardInIcPos = false;
    runOnUiThread(() -> tvIccid.setText("—"));
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Stock check  (§3.1.2  PM=30H)
  //
  //  st1 in out[4]:
  //    '0' = no card in stacker
  //    '1' = few cards
  //    '2' = enough cards
  // ─────────────────────────────────────────────────────────────────────

  private void doCheckStock() {
    log("── Check Stock");
    CommandResult cr = execCmd(addr + "43" + "31" + "30");
    if (!cr.ok) {
      logError("CheckStock", cr);
      runOnUiThread(() -> tvStockLevel.setText("ERROR"));
      return;
    }

    char st0 = cr.st0;  // card in machine: '0'=none '1'=at gate '2'=at IC/RF
    char st1 = cr.st1;  // stock: '0'=empty '1'=few '2'=enough
    char st2 = cr.st2;  // bin: '0'=not full '1'=full

    String stock;
    int stockColor;
    switch (st1) {
      case '2':
        stock = "HIGH";
        stockColor = 0xFF8EC97A;
        break;
      case '1':
        stock = "LOW";
        stockColor = 0xFFCF6679;
        break;
      default:
        stock = "EMPTY";
        stockColor = 0xFFCF6679;
        break;
    }

    String cardPos;
    switch (st0) {
      case '1':
        cardPos = "at gate";
        break;
      case '2':
        cardPos = "at IC/RF";
        break;
      default:
        cardPos = "none";
        break;
    }

    boolean binFull = (st2 == '1');

    log(String.format("st0='%c' st1='%c' st2='%c'  stock=%s  cardPos=%s  binFull=%b", st0, st1, st2,
        stock, cardPos, binFull));

    String finalStock = stock;
    int finalColor = stockColor;
    runOnUiThread(() -> {
      tvStockLevel.setText(finalStock);
      tvStockLevel.setTextColor(finalColor);
      tvStatus.setText("Card: " + cardPos + "  Bin full: " + binFull);
    });
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Sensor query  (§3.1.2  PM=31H)
  //
  //  Response DATA = S1..S8 (8 bytes ASCII, out[6..13])
  //    '0' = no card   '1' = card present
  // ─────────────────────────────────────────────────────────────────────

  private void doQuerySensors() {
    log("── Query Sensors");
    CommandResult cr = execCmd(addr + "43" + "31" + "31");
    if (!cr.ok) {
      logError("Sensors", cr);
      return;
    }

    StringBuilder sb = new StringBuilder();
    sb.append(String.format("st0='%c' st1='%c' st2='%c'\n", cr.st0, cr.st1, cr.st2));
    for (int i = 0; i < cr.dataLen && i < 8; i++) {
      char val = (char) (cr.out[6 + i] & 0xFF);
      sb.append(String.format("  S%d = '%c' (%s)\n", i + 1, val, val == '1' ? "CARD" : "empty"));
    }
    log(sb.toString());
    runOnUiThread(() -> tvStatus.setText(sb.toString().trim()));
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Status query  (§3.1.2  PM=30H)
  // ─────────────────────────────────────────────────────────────────────

  private void doQueryStatus() {
    log("── Query Status");
    CommandResult cr = execCmd(addr + "43" + "31" + "30");
    if (!cr.ok) {
      logError("Status", cr);
      return;
    }

    String cardPos = st0Desc(cr.st0);
    String stock = st1Desc(cr.st1);
    String bin = (cr.st2 == '1') ? "BIN FULL" : "bin ok";

    String s = String.format("Card: %s  Stock: %s  %s", cardPos, stock, bin);
    log(s);
    runOnUiThread(() -> tvStatus.setText(s));
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Card transport  (§3.1.3)
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Dispense one SIM: move from stacker to card-hold (gate) position. CM=32H PM=30H — card-hold
   * position. Card partially out, still retractable.
   */
  private void doDispense() {
    log("── Dispense → card-hold position");
    CommandResult cr = execCmd(addr + "43" + "32" + "30");
    if (!cr.ok) {
      logError("Dispense", cr);
      toast("Dispense failed");
      return;
    }
    logStatus("Dispense OK", cr);
    runOnUiThread(() -> tvStatus.setText("SIM at hold — push or retract"));
    toast("SIM dispensed to hold position");
  }

  /**
   * Push card to gate but keep hold (retractable). Same as doDispense but with the pickup-wait
   * signal. CM=32H PM=30H = card-hold at gate.
   */
  private void doPushToGate() {
    log("── Push to gate (retractable)");
    CommandResult cr = execCmd(addr + "43" + "32" + "30");
    if (!cr.ok) {
      logError("PushToGate", cr);
      toast("Push failed");
      return;
    }
    logStatus("Push OK", cr);
    runOnUiThread(() -> tvStatus.setText("SIM at gate — retractable"));
    toast("SIM at gate. Can still retract.");
  }

  /**
   * Full eject — move card out of gate completely. CM=32H PM=39H — moves card out of gate. Card is
   * released, cannot retract.
   */
  private void doEjectFull() {
    log("── Full eject (PM=39H)");
    CommandResult cr = execCmd(addr + "43" + "32" + "39");
    if (!cr.ok) {
      logError("FullEject", cr);
      toast("Eject failed");
      return;
    }
    logStatus("Eject OK", cr);
    cardInIcPos = false;
    runOnUiThread(() -> {
      tvStatus.setText("Card ejected — taken by user");
      tvIccid.setText("—");
    });
    toast("Card fully ejected.");
  }

  /**
   * Retract card back to recycle bin. CM=32H PM=33H — move card to error card bin.
   */
  private void doRetract() {
    log("── Retract to recycle bin (PM=33H)");
    if (cardInIcPos) {
      execCmd(addr + "43" + "51" + "31");  // CPU card power off first
      cardInIcPos = false;
    }
    CommandResult cr = execCmd(addr + "43" + "32" + "33");
    if (!cr.ok) {
      logError("Retract", cr);
      toast("Retract failed");
      return;
    }
    logStatus("Retract OK", cr);
    runOnUiThread(() -> tvStatus.setText("SIM retracted to bin"));
    toast("SIM retracted");
  }

  /**
   * Recycle via reset command. CM=30H PM=31H — reset + capture card to recycle bin.
   */
  private void doRecycle() {
    log("── Recycle (reset+capture PM=31H)");
    if (cardInIcPos) {
      execCmd(addr + "43" + "51" + "31");
      cardInIcPos = false;
    }
    CommandResult cr = execCmd(addr + "43" + "30" + "31");
    if (!cr.ok) {
      logError("Recycle", cr);
      toast("Recycle failed");
      return;
    }
    if (cr.dataLen > 0) {
      log("FW: " + asciiData(cr, 0, cr.dataLen));
    }
    logStatus("Recycle OK", cr);
    runOnUiThread(() -> {
      tvStatus.setText("SIM recycled");
      tvIccid.setText("—");
    });
    toast("SIM recycled");
  }

  /**
   * Dispensing motor backward — pulls card at stacker exit back into stacker. CM=32H PM=72H — motor
   * backward 200ms default. Useful to prevent cards obstructing stacker removal.
   */
  private void doMotorBackward() {
    log("── Motor backward (PM=72H)");
    CommandResult cr = execCmd(addr + "43" + "32" + "72");
    if (!cr.ok) {
      logError("MotorBackward", cr);
      return;
    }
    logStatus("Motor backward OK", cr);
  }

  /**
   * Enable card entry from gate (user can insert card). CM=33H PM=30H
   */
  private void doEnableEntry() {
    log("── Enable card entry");
    CommandResult cr = execCmd(addr + "43" + "33" + "30");
    if (!cr.ok) {
      logError("EnableEntry", cr);
      return;
    }
    logStatus("Entry enabled", cr);
    runOnUiThread(() -> tvStatus.setText("Card entry ENABLED"));
    toast("Card entry enabled");
  }

  /**
   * Disable card entry from gate. CM=33H PM=31H
   */
  private void doDisableEntry() {
    log("── Disable card entry");
    CommandResult cr = execCmd(addr + "43" + "33" + "31");
    if (!cr.ok) {
      logError("DisableEntry", cr);
      return;
    }
    logStatus("Entry disabled", cr);
    runOnUiThread(() -> tvStatus.setText("Card entry DISABLED"));
    toast("Card entry disabled");
  }

  // ─────────────────────────────────────────────────────────────────────
  //  IC / SIM chip  (§3.3)
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Activate card (cold-reset at 3V ISO) then read ICCID via APDU.
   */
  private void doReadIccid() {
    log("── Read ICCID");
    if (!activateCard()) {
      toast("Cannot activate card");
      return;
    }

    // SELECT EF_ICCID at MF level (2FE2)
    log("SELECT EF_ICCID (2FE2)...");
    CommandResult cr = sendApdu("00A4000002" + "2FE2");
    log("SELECT: " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));

    if (!cr.ok) {
      // Retry via explicit MF path 3F00 → 2FE2
      log("Retry via MF 3F00...");
      sendApdu("00A4000002" + "3F00");
      cr = sendApdu("00A4000002" + "2FE2");
      log("SELECT retry: " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));
      if (!cr.ok) {
        toast("SELECT EF_ICCID failed");
        return;
      }
    }

    // READ BINARY — 10 bytes = 20 BCD digits = ICCID
    log("READ BINARY 10 bytes...");
    cr = sendApdu("00B000000A");
    log("READ: " + (cr.ok ? "OK" : "FAIL") + "  SW=" + getSW(cr));
    if (!cr.ok) {
      toast("READ BINARY failed");
      return;
    }

    // DATA starts at out[6], 10 BCD bytes
    if (cr.dataLen < 10) {
      log("Response data too short: " + cr.dataLen);
      toast("Unexpected response length");
      return;
    }

    byte[] bcd = Arrays.copyOfRange(cr.out, 6, 16);
    String iccid = bcdToIccid(bcd);
    log("Raw BCD: " + Utils.bytes2HexStr(bcd, bcd.length, true));
    log("ICCID: " + iccid);

    runOnUiThread(() -> {
      tvIccid.setText(iccid);
      tvStatus.setText("ICCID read OK");
    });
    toast("ICCID: " + iccid);
  }

  /**
   * Auto-detect IC card type.  CM=50H PM=30H  (§3.2.1) Moves card to IC position, detects type,
   * returns Card_type (2 ASCII bytes).
   */
  private void doAutoDetectType() {
    log("── Auto-detect IC card type (CM=50H PM=30H)");
    CommandResult cr = execCmd(addr + "43" + "50" + "30");
    if (!cr.ok) {
      logError("AutoDetect", cr);
      return;
    }

    logStatus("AutoDetect", cr);

    if (cr.dataLen >= 2) {
      char t1 = (char) (cr.out[6] & 0xFF);
      char t2 = (char) (cr.out[7] & 0xFF);
      String typeName = icCardTypeName(t1, t2);
      log("Card type: " + t1 + t2 + " → " + typeName);
      runOnUiThread(() -> tvStatus.setText("Card type: " + typeName));
      toast("Card type: " + typeName);
    }
  }

  /**
   * Cold-reset at 3V ISO.  (§3.3.1)
   */
  private void doColdReset() {
    log("── Cold Reset (3V ISO)");
    activateCard();
  }

  /**
   * Warm-reset — re-init card without powering off contacts.  (§3.3.6) CM=51H PM=38H
   */
  private void doWarmReset() {
    log("── Warm Reset (CM=51H PM=38H)");
    if (!cardInIcPos) {
      log("Card not active — cold-resetting first");
      if (!activateCard()) {
        toast("Cannot activate card");
        return;
      }
    }
    CommandResult cr = execCmd(addr + "43" + "51" + "38");
    if (!cr.ok) {
      logError("WarmReset", cr);
      toast("Warm reset failed");
      return;
    }

    logStatus("Warm reset OK", cr);
    if (cr.dataLen >= 1) {
      char typeChar = (char) (cr.out[6] & 0xFF);
      cardType = (typeChar == '0') ? 0 : 1;
      byte[] atr = Arrays.copyOfRange(cr.out, 7, 6 + cr.dataLen);
      log("T=" + cardType + "  ATR=" + Utils.bytes2HexStr(atr, atr.length, true).toUpperCase());
      runOnUiThread(() -> tvStatus.setText("Warm reset OK  T=" + cardType));
    }
    toast("Warm reset OK");
  }

  /**
   * Deactivate / power off card contacts.  (§3.3.2) CM=51H PM=31H
   */
  private void doDeactivate() {
    log("── Deactivate (CM=51H PM=31H)");
    CommandResult cr = execCmd(addr + "43" + "51" + "31");
    if (!cr.ok) {
      logError("Deactivate", cr);
      toast("Deactivate failed");
      return;
    }
    logStatus("Deactivate OK", cr);
    cardInIcPos = false;
    runOnUiThread(() -> tvStatus.setText("Card contacts deactivated"));
    toast("Contacts deactivated");
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Device queries
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Read firmware version.  CM=DCH PM=31H  (§3.7)
   */
  private void doFirmwareVersion() {
    log("── Firmware Version (CM=DCH PM=31H)");
    CommandResult cr = execCmd(addr + "43" + "DC" + "31");
    if (!cr.ok) {
      logError("Firmware", cr);
      return;
    }
    String ver = asciiData(cr, 0, cr.dataLen);
    log("Firmware: " + ver);
    runOnUiThread(() -> tvStatus.setText("FW: " + ver));
  }

  /**
   * Read serial number.  CM=A2H PM=30H  (§3.6.8) Response DATA: len(1 byte) + SN(N bytes)
   */
  private void doSerialNumber() {
    log("── Serial Number (CM=A2H PM=30H)");
    CommandResult cr = execCmd(addr + "43" + "A2" + "30");
    if (!cr.ok) {
      logError("SerialNumber", cr);
      return;
    }
    if (cr.dataLen > 1) {
      // out[6] = length, out[7..] = SN as ASCII
      int snLen = cr.out[6] & 0xFF;
      String sn = asciiData(cr, 1, Math.min(snLen, cr.dataLen - 1));
      log("Serial: " + sn);
      runOnUiThread(() -> tvStatus.setText("SN: " + sn));
    }
  }

  /**
   * Read machine configuration.  CM=A3H PM=30H  (§3.6.9)
   */
  private void doDeviceConfig() {
    log("── Device Config (CM=A3H PM=30H)");
    CommandResult cr = execCmd(addr + "43" + "A3" + "30");
    if (!cr.ok) {
      logError("Config", cr);
      return;
    }
    String hex = Utils.bytes2HexStr(Arrays.copyOfRange(cr.out, 6, 6 + cr.dataLen), cr.dataLen, true)
        .toUpperCase();
    log("Config: " + hex);
    runOnUiThread(() -> tvStatus.setText("Config: " + hex));
  }

  /**
   * Read recycle bin counter.  CM=A5H PM=30H  (§3.6.10.1) Response DATA: count as 3 ASCII digits
   * "000"-"999"
   */
  private void doRecycleBinCount() {
    log("── Recycle Bin Counter (CM=A5H PM=30H)");
    CommandResult cr = execCmd(addr + "43" + "A5" + "30");
    if (!cr.ok) {
      logError("BinCounter", cr);
      return;
    }
    String count = asciiData(cr, 0, Math.min(3, cr.dataLen));
    log("Recycle bin count: " + count);
    runOnUiThread(() -> tvStatus.setText("Recycle bin count: " + count));
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Internal helpers
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Move card to IC position (skipping if sensor shows already there), then cold-reset at 3V ISO.
   * (§3.3.1  CM=51H PM=30H Vcc=35H)
   * <p>
   * Uses sensor query (§3.1.2 PM=31H) to check card presence and position: st0 = '2' → card already
   * at IC/RF position st0 = '1' → card at gate, need to move st0 = '0' + st1 = '0' → no card at all
   * → abort
   *
   * @return true if cold-reset succeeded and ATR received
   */
  private boolean activateCard() {
    log("Checking card position via sensor query...");
    CommandResult status = execCmd(addr + "43" + "31" + "30");  // PM=30H status request

    if (!status.ok) {
      log("Status query failed — cannot check card position.");
      return false;
    }

    log(String.format("st0='%c' st1='%c' st2='%c'", status.st0, status.st1, status.st2));

    // st0='0' and st1='0' → no card anywhere
    if (status.st0 == '0' && status.st1 == '0') {
      log("No card in machine (st0='0' st1='0').");
      runOnUiThread(() -> tvStatus.setText("No card in dispenser"));
      toast("No card present");
      return false;
    }

    // st0='2' → card already at IC/RF position — skip move
    boolean alreadyAtIc = (status.st0 == '2');

    if (!alreadyAtIc) {
      log("Moving card to IC position (CM=32H PM=31H)...");
      CommandResult move = execCmd(addr + "43" + "32" + "31");
      log("Move to IC: " + (move.ok ? "OK" : "FAIL e=" + getError(move)));
      if (!move.ok) {
        return false;
      }
    } else {
      log("Card already at IC/RF position — skipping move.");
    }

    // Cold-reset at 3V ISO  (Vcc=35H)
    log("Cold reset 3V ISO (CM=51H PM=30H Vcc=35H)...");
    CommandResult cr = execCmd(addr + "43" + "51" + "30" + "35");
    log("Cold reset: " + (cr.ok ? "OK" : "FAIL e=" + getError(cr)));

    if (cr.ok && cr.dataLen >= 1) {
      // out[6] = Type: '0'=T=0  '1'=T=1
      char typeChar = (char) (cr.out[6] & 0xFF);
      cardType = (typeChar == '0') ? 0 : 1;
      byte[] atr = Arrays.copyOfRange(cr.out, 7, 6 + cr.dataLen);
      log("T=" + cardType + "  ATR=" + Utils.bytes2HexStr(atr, atr.length, true).toUpperCase());
      cardInIcPos = true;
      runOnUiThread(() -> tvStatus.setText("Card active  T=" + cardType));
    }

    return cr.ok;
  }

  /**
   * Send a C-APDU to the currently active card using auto T=0/T=1. CM=51H PM=39H — auto protocol
   * selection.  (§3.3.7)
   */
  private CommandResult sendApdu(String apduHex) {
    // PM=39H = auto T=0/T=1 protocol selection
    return execCmd(addr + "43" + "51" + "39" + apduHex);
  }

  /**
   * Build APDU command with explicit protocol type. CM=51H PM=33H = T=0,  CM=51H PM=34H = T=1
   */
  private CommandResult sendApduTyped(String apduHex) {
    String pm = (cardType == 0) ? "33" : "34";
    return execCmd(addr + "43" + "51" + pm + apduHex);
  }

  /**
   * Extract SW1SW2 from APDU response data (last 2 bytes of DATA field). DATA starts at out[6],
   * length = cr.dataLen.
   */
  private String getSW(CommandResult cr) {
    if (cr.dataLen < 2) {
      return "????";
    }
    int swOffset = 6 + cr.dataLen - 2;
    return String.format("%02X%02X", cr.out[swOffset] & 0xFF, cr.out[swOffset + 1] & 0xFF);
  }

  /**
   * Read N ASCII bytes from DATA field starting at dataOffset.
   */
  private String asciiData(CommandResult cr, int dataOffset, int len) {
    int start = 6 + dataOffset;
    int end = Math.min(start + len, 6 + cr.dataLen);
    if (start >= end) {
      return "";
    }
    return new String(Arrays.copyOfRange(cr.out, start, end)).trim();
  }

  /**
   * Get error string from a failed response.
   */
  private String getError(CommandResult cr) {
    if (cr.outLen >= 5 && cr.out[0] == 0x4E) {
      return "" + (char) (cr.out[3] & 0xFF) + (char) (cr.out[4] & 0xFF);
    }
    return "rc=" + cr.rc;
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

  /**
   * IC card type name from 2-char type code (§3.2.1).
   */
  private String icCardTypeName(char t1, char t2) {
    if (t1 == '0' && t2 == '0') {
      return "Unknown";
    }
    if (t1 == '1') {
      return t2 == '0' ? "CPU T=0" : "CPU T=1";
    }
    if (t1 == '2') {
      return t2 == '0' ? "SLE4442" : "SLE4428";
    }
    if (t1 == '3') {
      int n = t2 - '0';
      String[] i2c = {"AT24C01", "AT24C02", "AT24C04", "AT24C08", "AT24C16", "AT24C32", "AT24C64",
          "AT24C128", "AT24C256"};
      return (n >= 0 && n < i2c.length) ? i2c[n] : "I2C unknown";
    }
    return "Unknown(" + t1 + t2 + ")";
  }

  private String st0Desc(char st0) {
    switch (st0) {
      case '1':
        return "card at gate";
      case '2':
        return "card at IC/RF";
      default:
        return "no card";
    }
  }

  private String st1Desc(char st1) {
    switch (st1) {
      case '2':
        return "enough cards";
      case '1':
        return "few cards";
      default:
        return "empty";
    }
  }

  private void logStatus(String label, CommandResult cr) {
    log(String.format("%s  st0='%c'(%s)  st1='%c'(%s)  st2='%c'", label, cr.st0, st0Desc(cr.st0),
        cr.st1, st1Desc(cr.st1), cr.st2));
  }

  private void logError(String label, CommandResult cr) {
    log(label + " FAILED  e=" + getError(cr) + "  rc=" + cr.rc);
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Command executor
  // ─────────────────────────────────────────────────────────────────────

  private static class CommandResult {

    boolean ok;
    byte[] out;
    int outLen;
    int rc;
    // Parsed from response
    char st0 = '0';
    char st1 = '0';
    char st2 = '0';
    int dataLen = 0;  // bytes of DATA after st2 (i.e. outLen - 6)
  }

  /**
   * Execute a hex command string and parse the response.
   * <p>
   * Command string format: [ADDR(2)][CMT='C'(2)][CM(2)][PM(2)][DATA...] Note: CMT byte '43H' is
   * always 'C' and is already included in the command strings we build (e.g. addr + "43" + "31" +
   * "30").
   * <p>
   * Response layout (driver strips framing): [0]  = 'P'(50H) success  or 'N'(4EH) error [1]  = CM
   * echo (ASCII) [2]  = PM echo (ASCII) [3]  = st0 or e1 (ASCII) [4]  = st1 or e0 (ASCII) [5]  =
   * st2 (success only, ASCII) [6+] = DATA (success only)
   */
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
      log("R_ExeCommandS threw — fallback to R_ExeCommandB");
      result.rc = crt.R_ExeCommandB(n_Fd, inBuf, length, result.out, outLenArr);
    }

    result.outLen = outLenArr[0] * 256 + outLenArr[1];
    result.ok =
        (result.rc == 0) && (result.outLen >= 3) && (result.out[0] == 0x50);   // 'P' = success

    // Parse status fields from success response
    if (result.ok && result.outLen >= 6) {
      result.st0 = (char) (result.out[3] & 0xFF);
      result.st1 = (char) (result.out[4] & 0xFF);
      result.st2 = (char) (result.out[5] & 0xFF);
      result.dataLen = result.outLen - 6;
    }

    log("← " + Utils.bytes2HexStr(result.out, result.outLen, true).toUpperCase() + "  [rc="
        + result.rc + "  len=" + result.outLen + "]");
    if (result.ok) {
      log(String.format("   st0='%c' st1='%c' st2='%c' dataLen=%d", result.st0, result.st1,
          result.st2, result.dataLen));
    } else if (result.outLen >= 5) {
      log(String.format("   ERROR e='%c%c'", (char) (result.out[3] & 0xFF),
          (char) (result.out[4] & 0xFF)));
    }

    return result;
  }

  private void toast(final String msg) {
    runOnUiThread(() -> Toast.makeText(SimKioskActivity.this, msg, Toast.LENGTH_LONG).show());
  }
}
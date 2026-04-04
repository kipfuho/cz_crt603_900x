package com.grg.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.cameraView.BitmapUtils;
import com.grg.camera.CustomDialogFragment;
import com.grg.camera.EMTestFragment;
import com.grg.camera.PermissionHelper;
import com.grg.camera.PermissionInterface;
import com.grg.camera.PermissionUtil;
import com.grg.face.bean.DetectResult;
import com.grg.face.core.GrgFaceDetecter;
import com.grg.face.utils.FaceCallBackAdapter;
import com.grg.face.utils.FaceDetectorUtils;
import com.grg.face.view.callback.ScanCallback;
import com.grg.grglog.GrgLog;
import com.grg.test.R;
import com.grg.test.databinding.ActivityTopCameraBinding;
import com.util.AppExecutors;
import com.util.AppUtils;
import com.util.FaceParam;
import com.util.FaceParamUtils;
import com.util.GrgFaceUtils;
import com.util.ProgressDialogUtils;
import com.util.SharedPreferencesUtils;
import com.util.TimeUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import xcrash.XCrash;

public class TopCameraActivity extends AppCompatActivity
    implements View.OnClickListener, PermissionInterface {

  private static final String TAG = "AllFunctionActivity";
  public static final String BASE_PATH =
      Environment.getExternalStorageDirectory().getPath() + "/";

  // Handler message codes
  private static final int MSG_SHOW_RESULT = 0;
  private static final int MSG_CLEAN_RESULT = 1;
  private static final int MSG_OUT_TIME = 2;
  private static final int MSG_SHOW_TIP = 3;

  private ActivityTopCameraBinding mBinding;
  private GrgFaceUtils mUtils;
  private FaceParam faceParam;
  private PermissionHelper permissionHelper;

  private boolean canShowResult = false;
  private long mStart;
  private int launguage;

  private boolean mInterrupt = false;
  private int mAgingIndex = 0;

  private final String[] mPermissions = {
      Manifest.permission.READ_PHONE_STATE,
      Manifest.permission.WRITE_EXTERNAL_STORAGE,
      Manifest.permission.READ_EXTERNAL_STORAGE,
      Manifest.permission.CAMERA,
      Manifest.permission.MANAGE_EXTERNAL_STORAGE
  };

  private final Handler mHandler = new Handler(msg -> {
    onMsgResult(msg);
    return true;
  });

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  @RequiresApi(api = Build.VERSION_CODES.R)
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().setFlags(
        WindowManager.LayoutParams.FLAG_FULLSCREEN,
        WindowManager.LayoutParams.FLAG_FULLSCREEN);

    launguage = (int) SharedPreferencesUtils.getParam(getApplicationContext(), "launguage", 0);
    setLanguage(launguage);

    mBinding = DataBindingUtil.setContentView(this, R.layout.activity_top_camera);

    permissionHelper = new PermissionHelper(this, this);
    permissionHelper.requestPermissions();

    initLogging();
    requestCameraPermissionIfNeeded();

    mUtils = GrgFaceUtils.getInstance(getApplicationContext());
    mUtils.openLog(true);

    faceParam = new FaceParam();
    updateParams();

    setupButtons();
    initSdk();

    mBinding.versionTv.setText(getString(R.string.version));

    mBinding.changeTv.setOnClickListener(v -> showLanguageDialog());

    AppExecutors.getInstance().diskIO().execute(BitmapUtils::deleteFaceFile);
  }

  @Override
  protected void onStop() {
    super.onStop();
    android.os.Process.killProcess(android.os.Process.myPid());
    System.exit(0);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_HOME) {
      android.os.Process.killProcess(android.os.Process.myPid());
      System.exit(0);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  // -------------------------------------------------------------------------
  // Init helpers
  // -------------------------------------------------------------------------

  @SuppressLint("SimpleDateFormat")
  private void initLogging() {
    String logBase = BASE_PATH + getPackageName();
    GrgLog.init(logBase + "/log/" + new SimpleDateFormat(GrgLog.DATE_FORMAT).format(new Date()));
    XCrash.init(this, new XCrash.InitParameters()
        .setLogDir(logBase + "/crash/" + new SimpleDateFormat("yyyy-MM-dd").format(new Date())));
  }

  private void requestCameraPermissionIfNeeded() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED
        || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this,
          new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }
  }

  private void setupButtons() {
    int[] ids = {
        R.id.setting_bt, R.id.stop_bt, R.id.start_bt, R.id.get_pic_bt,
        R.id.open_bt, R.id.close_bt, R.id.open_v_bt, R.id.em_test_bt,
        R.id.aging_test_bt, R.id.get_pic_single_bt, R.id.scan_bt
    };
    for (int id : ids) {
      mBinding.getRoot().findViewById(id).setOnClickListener(this);
    }

    mBinding.emTestBt.setVisibility(faceParam.isHavaEM ? View.VISIBLE : View.GONE);
    setBtnBy(0);
  }

  private void initSdk() {
    ProgressDialogUtils.showProgressDialog(this, getString(R.string.tips),
        getString(R.string.loading));
    AppExecutors.getInstance().diskIO().execute(() -> {
      mUtils.setLazyLoad(false);
      mUtils.setShowFrame(true);
      mUtils.init(getApplicationContext(), new FaceDetectorUtils.InitCallBack() {
        @Override
        public void initSuccess() {
          ProgressDialogUtils.dismissProgressDialog();
        }

        @Override
        public void initFail(String msg) {
          runOnUiThread(() -> {
            ProgressDialogUtils.dismissProgressDialog();
            mBinding.timesTv.setText(msg);
          });
        }
      });
    });
  }

  // -------------------------------------------------------------------------
  // Handler / messaging
  // -------------------------------------------------------------------------

  private void sendMsg(int what, Object obj) {
    mHandler.sendMessage(mHandler.obtainMessage(what, obj));
  }

  private void sendMsgDelayed(int what, Object obj, long delay) {
    mHandler.removeMessages(what, obj);
    mHandler.sendMessageDelayed(mHandler.obtainMessage(what, obj), delay);
  }

  private void onMsgResult(Message msg) {
    switch (msg.what) {
      case MSG_SHOW_RESULT:
        mBinding.timesTv.setText(msg.obj.toString());
        mUtils.showResult(msg.obj.toString(), true);
        break;
      case MSG_SHOW_TIP:
        mBinding.tip.setText(msg.obj.toString());
        break;
      case MSG_CLEAN_RESULT:
        mBinding.timesTv.setText("");
        mBinding.face1Iv.setImageBitmap(null);
        mBinding.face2Iv.setImageBitmap(null);
        mUtils.showResult("", false);
        break;
      case MSG_OUT_TIME:
        sendMsgDelayed(MSG_CLEAN_RESULT, null, 1000);
        sendMsg(MSG_SHOW_RESULT, getString(R.string.timeout));
        canShowResult = false;
        mUtils.stopLiveDetect();
        break;
    }
  }

  // -------------------------------------------------------------------------
  // Click handler
  // -------------------------------------------------------------------------

  @Override
  public void onClick(View view) {
    int id = view.getId();

    if (id == R.id.setting_bt) {
      openSettings();
    } else if (id == R.id.start_bt) {
      startLiveDetect();
    } else if (id == R.id.open_bt) {
      openBinocularCamera();
    } else if (id == R.id.open_v_bt) {
      openVisibleCamera();
    } else if (id == R.id.close_bt || id == R.id.stop_bt) {
      stopCamera();
    } else if (id == R.id.scan_bt) {
      startScan();
    } else if (id == R.id.get_pic_bt) {
      takeBinocularPicture();
    } else if (id == R.id.get_pic_single_bt) {
      takeSinglePicture();
    } else if (id == R.id.aging_test_bt) {
      toggleAgingTest();
    } else if (id == R.id.em_test_bt) {
      new EMTestFragment().show(getSupportFragmentManager(), "em");
    }
  }

  // -------------------------------------------------------------------------
  // Camera / detection actions
  // -------------------------------------------------------------------------

  private void openSettings() {
    Runnable showDialog = () -> {
      reSet();
      new CustomDialogFragment().show(getSupportFragmentManager(), "dialog");
    };
    if (mUtils.isOpen()) {
      mUtils.stopCamera();
      mUtils.setFaceCallBack(null);
      mBinding.settingBt.postDelayed(showDialog, 500);
    } else {
      showDialog.run();
    }
  }

  private void startLiveDetect() {
    reSet();
    mUtils.setFaceCallBack(null);
    mUtils.setFaceCallBack(mLiveFaceCallBack);
    canShowResult = true;
    mBinding.timesTv.setText(getString(R.string.face_camera));
    mUtils.showResult(getString(R.string.face_camera), true);
    mStart = System.currentTimeMillis();
    mUtils.startLiveDetect();
    mUtils.clearFrame();
    mUtils.setShowFrame(true);
    mHandler.removeMessages(MSG_OUT_TIME);
    faceParam.pitchThreshold = 15.0f;
    mUtils.setFaceParam(faceParam);
    sendMsgDelayed(MSG_OUT_TIME, null, 10000);
  }

  private void openBinocularCamera() {
    if (mUtils.isOpen()) {
      mBinding.timesTv.setText("请先关闭摄像头");
      return;
    }
    setBtnBy(1);
    updateParams();
    faceParam.location = "0|0|320|240";
    faceParam.resolution = "640*480";
    faceParam.mixFaceWidth = 500;
    faceParam.mixFaceHeight = 500;
    faceParam.faceScaleX = 0.1;
    faceParam.faceScaleY = 0.3;
    faceParam.saveError = false;
    startCameraAsync(() -> mUtils.startCamera(getApplicationContext(), faceParam));
  }

  private void openVisibleCamera() {
    if (mUtils.isOpen()) {
      mBinding.timesTv.setText(getString(R.string.plug_in_camera));
      return;
    }
    setBtnBy(2);
    updateParams();
    faceParam.location = "340|180|640|480";
    faceParam.isShowLine = true;
    faceParam.resolution = "640*480";
    startCameraAsync(() -> mUtils.StartVICamera(getApplicationContext(), faceParam,
        code -> Log.i(TAG, code == 0 ? "扫码摄像头已打开" : "扫码摄像头打开失败:" + code)));
  }

  private void startCameraAsync(Runnable openTask) {
    if (!mUtils.isHaveGrgCamera(getApplicationContext())) {
      mBinding.timesTv.setText(getString(R.string.plug_in_camera));
      return;
    }
    AppExecutors.getInstance().diskIO().execute(() -> {
      openTask.run();
      runOnUiThread(() -> canShowResult = false);
    });
  }

  private void stopCamera() {
    setBtnBy(0);
    mUtils.stopCamera();
    mUtils.setFaceCallBack(null);
    mBinding.face1Iv.postDelayed(this::reSet, 300);
  }

  private void startScan() {
    mBinding.timesTv.setText(getString(R.string.scan));
    mBinding.tip.setText("");
    long startTime = System.currentTimeMillis();
    mUtils.startScan(1000 * 90, new ScanCallback() {
      @Override
      public void scanResult(String result) {
        runOnUiThread(() -> {
          mUtils.stopScan();
          mBinding.tip.setText(result + "\n" + getString(R.string.scan_taking)
              + (System.currentTimeMillis() - startTime));
        });
      }

      @Override
      public void timeOut() {
        mUtils.stopScan();
        runOnUiThread(() -> mBinding.timesTv.setText(
            getString(R.string.scan_failed_taking) + (System.currentTimeMillis() - startTime)));
      }

      @Override
      public void checkAuthFailed() {
        mUtils.stopScan();
        runOnUiThread(() -> mBinding.timesTv.setText("摄像头未授权"));
      }
    });
  }

  private void takeBinocularPicture() {
    reSet();
    mUtils.setFaceParam(faceParam);
    AppExecutors.getInstance().diskIO().execute(() ->
        mUtils.getPic(new GrgFaceDetecter.GetPicCallBack() {
          @Override
          public void getPic(Bitmap bitmap1, Bitmap bitmap2) {
            long time = System.currentTimeMillis();
            BitmapUtils.saveBitmap(TimeUtils.formatHMS2(time) + "_拍照_V", bitmap1);
            BitmapUtils.saveBitmap(TimeUtils.formatHMS2(time) + "_拍照_IR", bitmap2);
            runOnUiThread(() -> {
              mBinding.face1Iv.setImageBitmap(bitmap1);
              mBinding.face2Iv.setImageBitmap(bitmap2);
            });
          }
        }));
  }

  private void takeSinglePicture() {
    reSet();
    Bitmap pic = mUtils.getSinglePic();
    BitmapUtils.saveBitmap("单目拍照_" + System.currentTimeMillis(), pic);
    mBinding.face1Iv.setImageBitmap(pic);
  }

  // -------------------------------------------------------------------------
  // Aging test
  // -------------------------------------------------------------------------

  private void toggleAgingTest() {
    if (mBinding.agingTestBt.getText().toString().equals(getString(R.string.open_aging_test))) {
      startAgingTest();
    } else {
      stopAgingTest();
    }
  }

  private void startAgingTest() {
    mAgingIndex = 0;
    mInterrupt = false;
    mBinding.agingTestBt.setText(getString(R.string.close_aging_test));
    AppExecutors.getInstance().diskIO().execute(() -> {
      while (!mInterrupt) {
        mAgingIndex++;
        runAndLog("打开", () -> runOnUiThread(() -> mBinding.openBt.performClick()), 4000);
        runAndLog("关闭", () -> {
          runOnUiThread(() -> {
            mBinding.stopBt.performClick();
            mBinding.face1Iv.setImageBitmap(null);
            mBinding.face2Iv.setImageBitmap(null);
          });
        }, 2000);
      }
    });
  }

  private void runAndLog(String action, Runnable task, long sleepAfter) {
    if (mInterrupt) {
      return;
    }
    try {
      task.run();
      GrgLog.i("老化测试", "第 " + mAgingIndex + " 次" + action + "正常");
      runOnUiThread(() -> mBinding.timesTv.setText(mAgingIndex + " " + action));
    } catch (Exception e) {
      GrgLog.e("老化测试", "第 " + mAgingIndex + " 次" + action + "异常: " + e.getMessage());
    }
    try {
      Thread.sleep(sleepAfter);
    } catch (InterruptedException ignored) {
    }
  }

  private void stopAgingTest() {
    mInterrupt = true;
    mBinding.agingTestBt.setText(getString(R.string.open_aging_test));
    runOnUiThread(() -> {
      try {
        mBinding.stopBt.performClick();
      } catch (Exception ignored) {
      }
    });
  }

  // -------------------------------------------------------------------------
  // UI helpers
  // -------------------------------------------------------------------------

  private void reSet() {
    mUtils.setFaceParam(faceParam);
    mBinding.face1Iv.setImageBitmap(null);
    mBinding.face2Iv.setImageBitmap(null);
    mBinding.timesTv.setText("");
    mBinding.tip.setText("");
    mUtils.showResult("", false);
    mHandler.removeMessages(MSG_OUT_TIME);
  }

  private void updateParams() {
    faceParam = FaceParamUtils.readConfigFromDisk(getApplicationContext(), "face", faceParam);
  }

  private void setLanguage(int lang) {
    Resources resources = getResources();
    Configuration config = resources.getConfiguration();
    config.locale = (lang == 1) ? Locale.CHINESE : Locale.ENGLISH;
    resources.updateConfiguration(config, resources.getDisplayMetrics());
  }

  private void showLanguageDialog() {
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.tips))
        .setMessage(getString(R.string.change_language))
        .setPositiveButton(getString(R.string.yes), (d, w) -> {
          SharedPreferencesUtils.setParam(getApplicationContext(), "launguage",
              launguage == 0 ? 1 : 0);
          AppUtils.reStartApp(getApplicationContext());
        })
        .setNegativeButton(getString(R.string.no), (d, w) -> d.dismiss())
        .show();
  }

  /**
   * Set button enabled/color states. 0=idle, 1=binocular open, 2=visible open
   */
  public void setBtnBy(int type) {
    boolean binocularOpen = (type == 1);
    boolean visibleOpen = (type == 2);
    boolean idle = (type == 0);

    setBtn(mBinding.openBt, idle, idle);
    setBtn(mBinding.openVBt, idle || visibleOpen, idle || visibleOpen);
    setBtn(mBinding.startBt, binocularOpen, binocularOpen);
    setBtn(mBinding.getPicBt, binocularOpen, binocularOpen);
    setBtn(mBinding.getPicSingleBt, visibleOpen, visibleOpen);
    setBtn(mBinding.scanBt, visibleOpen, visibleOpen);
  }

  private void setBtn(View btn, boolean enabled, boolean white) {
    btn.setClickable(enabled);
    if (btn instanceof android.widget.TextView) {
      ((android.widget.TextView) btn).setTextColor(white ? Color.WHITE : Color.GRAY);
    }
  }

  public void exit(View view) {
    mUtils.stopCamera();
    mBinding.emTestBt.postDelayed(AppUtils::exitApp, 1000);
  }

  // -------------------------------------------------------------------------
  // Face detection callback
  // -------------------------------------------------------------------------

  private final FaceDetectorUtils.FaceCallBack mLiveFaceCallBack = new FaceCallBackAdapter() {
    public void detectResult(DetectResult detectResult, float[] anglesArray, long liveTime) {
      runOnUiThread(() -> {
        if (!canShowResult) {
          return;
        }

        if (detectResult.code != 0) {
          mBinding.face1Iv.setImageBitmap(null);
          mBinding.face2Iv.setImageBitmap(null);
          mHandler.removeMessages(MSG_CLEAN_RESULT);
          sendMsg(MSG_SHOW_RESULT, detectResult.code + " " + detectResult.msg);
        } else {
          mHandler.removeMessages(MSG_OUT_TIME);
          String result = "【" + detectResult.msg + "】 ";
          mUtils.showResult(result, true);
          mUtils.stopLiveDetect();
          mBinding.face1Iv.setImageBitmap(detectResult.bitmap1);
          mBinding.face2Iv.setImageBitmap(detectResult.bitmap2);
          BitmapUtils.saveBitmap("Live_V", detectResult.originBitmap1);
          BitmapUtils.saveBitmap("Live_IR", detectResult.originBitmap2);
          sendMsg(MSG_SHOW_RESULT, result);
          sendMsg(MSG_SHOW_TIP, getString(R.string.taking) + liveTime + "ms");
        }
      });
    }

    @Override
    public void showMsg(String msg) {
      runOnUiThread(() -> {
        if (!canShowResult) {
          return;
        }
        mBinding.timesTv.setText(msg);
      });
    }
  };

  // -------------------------------------------------------------------------
  // Permissions
  // -------------------------------------------------------------------------

  @Override
  public int getPermissionsRequestCode() {
    return 0;
  }

  @Override
  public String[] getPermissions() {
    return mPermissions;
  }

  @Override
  public void requestPermissionsSuccess() {
  }

  @Override
  public void requestPermissionsFail() {
    PermissionUtil.getDeniedPermissions(this, mPermissions);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    if (!permissionHelper.requestPermissionsResult(code, perms, results)) {
      super.onRequestPermissionsResult(code, perms, results);
    }
  }
}
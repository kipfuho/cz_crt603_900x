package com.grg.camera;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;

import com.grg.test.BuildConfig;
import com.grg.test.databinding.CustomDialogBinding;
import com.util.FaceParam;
import com.grg.test.R;
import com.util.FaceParamUtils;

public class CustomDialogFragment extends DialogFragment {

  private FaceParam mFaceParam;

  private CustomDialogBinding mBinding;

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    mBinding = DataBindingUtil.inflate(LayoutInflater.from(getActivity()), R.layout.custom_dialog,
        null, false);
    // 配置绑定的View模型，如果有的话
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setView(mBinding.getRoot());
    if (BuildConfig.UVC) {
      mBinding.cameraIdLl.setVisibility(View.GONE);
      mBinding.uvcCameraIdLl.setVisibility(View.VISIBLE);
    } else {
      mBinding.cameraIdLl.setVisibility(View.VISIBLE);
      mBinding.uvcCameraIdLl.setVisibility(View.GONE);
    }
    mBinding.camera1IdSp.setSelection(getCameraIdSelection(mFaceParam.camera1Id));
    mBinding.camera2IdSp.setSelection(getCameraIdSelection(mFaceParam.camera2Id));
    mBinding.camera3IdSp.setSelection(getCameraIdSelection(mFaceParam.camera3Id));
    mBinding.uvcCameraIdSp.setSelection(getUVCCameraIdSelection(mFaceParam.id));
    mBinding.openLedSp.setSelection(mFaceParam.isOpenLed ? 0 : 1);

    mBinding.openAngleSp.setSelection(mFaceParam.needAngle ? 0 : 1);
    mBinding.openAutoFocusSp.setSelection(mFaceParam.autoFucus ? 0 : 1);
    mBinding.reverseFrameSp.setSelection(mFaceParam.isReverseFrame ? 0 : 1);

    mBinding.openOcclusionSp.setSelection(mFaceParam.needOcclusion ? 0 : 1);
    mBinding.openEyeSp.setSelection(mFaceParam.needEyeClose ? 0 : 1);
    mBinding.openDiffSp.setSelection(mFaceParam.needDiff ? 0 : 1);

    mBinding.camera1RotationSp.setSelection(getCameraAngleSelection(mFaceParam.camera1Rotation));
    mBinding.camera1MirrorSp.setSelection(mFaceParam.camera1Mirror ? 0 : 1);
    mBinding.camera1FlipSp.setSelection(mFaceParam.camera1Flip ? 0 : 1);

    mBinding.camera2RotationSp.setSelection(getCameraAngleSelection(mFaceParam.camera2Rotation));
    mBinding.camera2MirrorSp.setSelection(mFaceParam.camera2Mirror ? 0 : 1);
    mBinding.camera2FlipSp.setSelection(mFaceParam.camera2Flip ? 0 : 1);

    mBinding.camera3RotationSp.setSelection(getCameraAngleSelection(mFaceParam.camera3Rotation));
    mBinding.camera3MirrorSp.setSelection(mFaceParam.camera3Mirror ? 0 : 1);
    mBinding.camera3FlipSp.setSelection(mFaceParam.camera3Flip ? 0 : 1);

    mBinding.camera1CheckSp.setSelection(getCameraAngleSelection(mFaceParam.camera1CheckRotation));
    mBinding.input1MirrorBt.setSelection(mFaceParam.input1Mirror ? 0 : 1);
    mBinding.input1FlipSp.setSelection(mFaceParam.input1Flip ? 0 : 1);

    mBinding.camera2CheckSp.setSelection(getCameraAngleSelection(mFaceParam.camera2CheckRotation));
    mBinding.input2MirrorBt.setSelection(mFaceParam.input2Mirror ? 0 : 1);
    mBinding.input2FlipSp.setSelection(mFaceParam.input2Flip ? 0 : 1);

    mBinding.camera3CheckSp.setSelection(getCameraAngleSelection(mFaceParam.camera3CheckRotation));
    mBinding.input3MirrorBt.setSelection(mFaceParam.input3Mirror ? 0 : 1);
    mBinding.input3FlipSp.setSelection(mFaceParam.input3Flip ? 0 : 1);

    mBinding.openFullCameraSp.setSelection(mFaceParam.userFullCamera ? 0 : 1);
    Log.i("grg", "红外显示进入：" + mFaceParam.isShowIr);
    mBinding.showIrSp.setSelection(mFaceParam.isShowIr ? 0 : 1);
    mBinding.haveEmSp.setSelection(mFaceParam.isHavaEM ? 0 : 1);

    mBinding.locationSp.setSelection(getLocationSelection(mFaceParam.location));
    mBinding.faceFrameSp.setSelection(mFaceParam.needFaceFrame ? 0 : 1);
    mBinding.darkLightSp.setSelection(mFaceParam.darkLightMode ? 0 : 1);

    mBinding.rotationSp.setSelection(mFaceParam.rotationDirection ? 0 : 1);
    mBinding.angleSp.setSelection(getAngleSelection(mFaceParam.angleEm));

    mBinding.saveConfigBt.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        mFaceParam.id = Integer.parseInt((String) mBinding.uvcCameraIdSp.getSelectedItem());
        mFaceParam.camera1Id = Integer.parseInt((String) mBinding.camera1IdSp.getSelectedItem());
        mFaceParam.camera2Id = Integer.parseInt((String) mBinding.camera2IdSp.getSelectedItem());
        mFaceParam.camera3Id = Integer.parseInt((String) mBinding.camera3IdSp.getSelectedItem());
        mFaceParam.isOpenLed = mBinding.openLedSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));

        mFaceParam.needAngle = mBinding.openAngleSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.autoFucus = mBinding.openAutoFocusSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.isReverseFrame = mBinding.reverseFrameSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));

        mFaceParam.needOcclusion = mBinding.openOcclusionSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.needEyeClose = mBinding.openEyeSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.needDiff = mBinding.openDiffSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera1Rotation = Integer.parseInt(
            mBinding.camera1RotationSp.getSelectedItem().toString());
        mFaceParam.camera1Mirror = mBinding.camera1MirrorSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera1Flip = mBinding.camera1FlipSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera2Rotation = Integer.parseInt(
            mBinding.camera2RotationSp.getSelectedItem().toString());
        mFaceParam.camera2Mirror = mBinding.camera2MirrorSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera2Flip = mBinding.camera2FlipSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera3Rotation = Integer.parseInt(
            mBinding.camera3RotationSp.getSelectedItem().toString());
        mFaceParam.camera3Mirror = mBinding.camera3MirrorSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera3Flip = mBinding.camera3FlipSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera1CheckRotation = Integer.parseInt(
            mBinding.camera1CheckSp.getSelectedItem().toString());
        mFaceParam.input1Mirror = mBinding.input1MirrorBt.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.input1Flip = mBinding.input1FlipSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera2CheckRotation = Integer.parseInt(
            mBinding.camera2CheckSp.getSelectedItem().toString());
        mFaceParam.input2Mirror = mBinding.input2MirrorBt.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.input2Flip = mBinding.input2FlipSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.camera3CheckRotation = Integer.parseInt(
            mBinding.camera3CheckSp.getSelectedItem().toString());
        mFaceParam.input3Mirror = mBinding.input3MirrorBt.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.input3Flip = mBinding.input3FlipSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.userFullCamera = mBinding.openFullCameraSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        Log.i("grg",
            "红外显示设置前：" + mFaceParam.isShowIr + "  " + mBinding.showIrSp.getSelectedItem()
                .toString() + "  " + getString(R.string.yes));
        mFaceParam.isShowIr = mBinding.showIrSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.isHavaEM = mBinding.haveEmSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        Log.i("grg", "红外显示设置后：" + mFaceParam.isShowIr);
        mFaceParam.location = mBinding.locationSp.getSelectedItem().toString();
        mFaceParam.needFaceFrame = mBinding.faceFrameSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.darkLightMode = mBinding.darkLightSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));

        mFaceParam.rotationDirection = mBinding.rotationSp.getSelectedItem().toString()
            .equals(getString(R.string.yes));
        mFaceParam.angleEm = Integer.parseInt(mBinding.angleSp.getSelectedItem().toString());
        FaceParamUtils.saveConfig(getContext(), "face", mFaceParam);
        dismiss();
      }
    });

    mBinding.resetConfigBt.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        mFaceParam = new FaceParam();
        FaceParamUtils.saveConfig(getContext(), "face", mFaceParam);
        dismiss();
      }
    });

    mBinding.cancelBt.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        dismiss();
      }
    });

    // 设置其他对话框属性
    return builder.create();
  }

  private int getLocationSelection(String location) {
    if (location.equals("0|0|640|480")) {
      return 0;
    }
    if (location.equals("0|0|320|240")) {
      return 1;
    }
    return 0;
  }

  private int getCameraAngleSelection(int angle) {
    if (angle == 0) {
      return 0;
    }
    if (angle == 90) {
      return 1;
    }
    if (angle == 180) {
      return 2;
    }
    if (angle == 270) {
      return 3;
    }
    return 0;
  }

  private int getAngleSelection(int angle) {
    if (angle == 20) {
      return 0;
    }
    if (angle == 30) {
      return 1;
    }
    if (angle == 60) {
      return 2;
    }

    return 0;
  }


  private int getUVCCameraIdSelection(int id) {
    if (id == 0) {
      return 0;
    }
    if (id == 1) {
      return 1;
    }
    return 0;
  }

  private int getCameraIdSelection(int id) {
    if (id == -1) {
      return 0;
    }
    if (id == 0) {
      return 1;
    }
    if (id == 1) {
      return 2;
    }
    if (id == 2) {
      return 3;
    }
    if (id == 3) {
      return 4;
    }
    return 0;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mFaceParam = FaceParamUtils.readConfigFromDisk(getContext(), "face", mFaceParam);
  }

  private FaceParam getTestParam() {
    FaceParam faceParam = new FaceParam();
    faceParam.camera1Id = 0;
    faceParam.camera2Id = 2;
    faceParam.camera3Id = 1;
    faceParam.autoFucus = false;
    faceParam.location = "0|0|320|240";
    faceParam.resolution = "640*480";
    faceParam.isHavaEM = false;
    faceParam.faceScaleX = 0.1;
    faceParam.faceScaleY = 0.1;
    faceParam.isReverseFrame = false;
    faceParam.camera1Rotation = 90;
    faceParam.camera2Rotation = 180;
    faceParam.camera3Rotation = 270;
    faceParam.camera1CheckRotation = 90;
    faceParam.camera2CheckRotation = 270;
    faceParam.camera3CheckRotation = 180;
    faceParam.input1Flip = false;
    faceParam.input2Flip = false;
    faceParam.input3Flip = false;
    faceParam.camera2Mirror = false;
    faceParam.input1Mirror = false;
    faceParam.input3Mirror = false;
    faceParam.isShowIr = false;
    faceParam.needOcclusion = false;
    faceParam.needDiff = false;
    faceParam.needEyeClose = false;
    faceParam.needAngle = false;
    return faceParam;
  }
}
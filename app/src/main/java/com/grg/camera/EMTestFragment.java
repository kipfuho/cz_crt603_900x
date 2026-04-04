package com.grg.camera;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import com.grg.test.R;
import com.grg.test.databinding.LayoutEmBinding;
import com.util.EMUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;

public class EMTestFragment extends DialogFragment implements View.OnClickListener {

    private EMUtils mEMUtils;

    private LayoutEmBinding mBinding;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        mBinding = DataBindingUtil.inflate(LayoutInflater.from(getActivity()), R.layout.layout_em, null, false);
        // 配置绑定的View模型，如果有的话
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setView(mBinding.getRoot());

        mBinding.upBt.setOnClickListener(this);
        mBinding.downBt.setOnClickListener(this);
        mBinding.resetBt.setOnClickListener(this);
        mBinding.findBt.setOnClickListener(this);
        mBinding.interruptBt.setOnClickListener(this);

   /*     FaceParam faceParam = FaceParamUtils.readConfigFromDisk(getContext(), fileName, new FaceParam());
        EMUtils.getInstance(getContext()).start(faceParam);*/

        // 设置其他对话框属性
        return builder.create();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mEMUtils = EMUtils.getInstance(getContext());

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.reset_bt:
                mEMUtils.reset();
                break;
            case R.id.interrupt_bt:
                mEMUtils.interruptCommand();
                break;
            case R.id.up_bt:
                mEMUtils.up();
                break;
            case R.id.down_bt:
                mEMUtils.down();
                break;
            case R.id.find_bt:
                mEMUtils.findPeople();
                break;
        }
    }


}
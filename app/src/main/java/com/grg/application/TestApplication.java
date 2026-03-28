package com.grg.application;

import android.app.Application;

import com.lib.common.base.BaseApplication;

/**
 * @author yellow'baby
 * @Description GRG Zhuoshi
 * @Date 2023/4/27 14:45
 */
public class TestApplication extends BaseApplication {

  public static Application INSTANCE;

  @Override
  public void onCreate() {
    super.onCreate();
    INSTANCE = this;

  }
}

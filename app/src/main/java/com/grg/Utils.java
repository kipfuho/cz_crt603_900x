package com.grg;

import android.graphics.Bitmap;

public final class Utils {

  private Utils() {
    // Prevent instantiation
  }

  public static Bitmap toSoftwareBitmap(Bitmap src) {
    if (src == null) {
      return null;
    }

    // Force a software-backed copy, decoupled from gralloc
    Bitmap soft = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
    android.graphics.Canvas canvas = new android.graphics.Canvas(soft);
    canvas.drawBitmap(src, 0, 0, null);
    return soft;
  }
}

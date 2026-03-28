package com.grg.crt;

import android.util.Log;

import com.device.Crt900x;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;

import java.util.Arrays;

public class CrtCardService extends CardService {

  private final Crt900x reader;
  private final String readerFd;

  public CrtCardService(Crt900x reader, String readerFd) {
    this.reader = reader;
    this.readerFd = readerFd;
  }

  @Override
  public void open() {
    reader.CrtOpenReader(this.readerFd.getBytes(), 0);
  }

  @Override
  public boolean isOpen() {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public byte[] getATR() {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public void close() {
    reader.CrtCloseReader();
  }

  @Override
  public boolean isConnectionLost(Exception e) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public ResponseAPDU transmit(CommandAPDU commandAPDU) throws CardServiceException {
    if (commandAPDU == null) {
      throw new CardServiceException("CommandAPDU cannot be null");
      // Or return null if you prefer:
      // return null;
    }

    byte[] cmd = commandAPDU.getBytes();
    Log.i("CrtCardService", "Transmit: " + Utils.bytes2HexStr(cmd, cmd.length, true));

    byte[] responseBuffer = new byte[4096];
    int[] responseLength = new int[1];

    int ret = reader.CrtSendAPDU('A', cmd.length, cmd, responseLength, responseBuffer);

    if (ret != 0) {
      throw new CardServiceException("CrtSendAPDU failed, ret=" + ret);
    }

    byte[] resp = Arrays.copyOf(responseBuffer, responseLength[0]);
    return new ResponseAPDU(resp);
  }
}

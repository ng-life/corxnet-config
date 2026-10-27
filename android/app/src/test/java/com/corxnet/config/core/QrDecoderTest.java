package com.corxnet.config.core;

import static org.junit.Assert.assertEquals;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.Test;

public class QrDecoderTest {
  @Test
  public void decodesDeviceAndConfigurationQrPixelsOffline() throws Exception {
    for (String json :
        new String[] {
          "{\"mac\":\"0090E2D72060\"}",
          "{\"network\":{\"port\":1883},\"mqtt\":{\"heartbeat_content\":\"alive\",\"heartbeat_interval\":30}}"
        }) {
      BitMatrix qr = new QRCodeWriter().encode(json, BarcodeFormat.QR_CODE, 400, 400);
      byte[] pixels = new byte[400 * 400];
      for (int y = 0; y < 400; y++)
        for (int x = 0; x < 400; x++) pixels[y * 400 + x] = qr.get(x, y) ? 0 : (byte) 255;
      BinaryBitmap bitmap =
          new BinaryBitmap(
              new HybridBinarizer(
                  new PlanarYUVLuminanceSource(pixels, 400, 400, 0, 0, 400, 400, false)));
      String decoded = new MultiFormatReader().decode(bitmap).getText();
      assertEquals(json, decoded);
      if (json.contains("\"mac\"")) assertEquals("0090E2D72060", QrConfig.device(decoded));
      else assertEquals("1883", QrConfig.configuration(decoded).network.get("port"));
    }
  }
}

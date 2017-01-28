/* 
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Oleg Kurbatov (o.v.kurbatov@gmail.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.firmata4j.firmata;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import org.firmata4j.IODevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


/**
 * Implements {@link IODevice} that is using Firmata protocol over JSSC.
 *
 * @author Oleg Kurbatov &lt;o.v.kurbatov@gmail.com&gt;
 */
public class FirmataDevice extends AbstractFirmataDevice implements IODevice, SerialPortEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFirmataDevice.class);

    private final SerialPort port;
    private final BlockingQueue<byte[]> byteQueue = new ArrayBlockingQueue<>(128);
    private final FirmataParser parser = new FirmataParser(byteQueue);
    private final Thread parserExecutor = new Thread(parser, "firmata-parser-thread");

    /**
     * Constructs FirmataDevice instance on specified port.
     *
     * @param portName the port name the device is connected to
     */
    public FirmataDevice(String portName) {
        this.port = new SerialPort(portName);
    }

    @Override
    protected void openPort() throws IOException {
        try {
            parserExecutor.start();
            if (!port.isOpened()) {
                port.openPort();
                port.setParams(
                    SerialPort.BAUDRATE_57600,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);
            }
            port.setEventsMask(SerialPort.MASK_RXCHAR);
            port.addEventListener(this);
        } catch (SerialPortException ex) {
            parserExecutor.interrupt();
            throw new IOException("Cannot start firmata device", ex);
        }
    }

    @Override
    protected void closePort() throws IOException {
        parserExecutor.interrupt();
        try {
            port.purgePort(SerialPort.PURGE_RXCLEAR | SerialPort.PURGE_TXCLEAR);
            port.closePort();
        } catch (SerialPortException ex) {
            throw new IOException("Cannot properly stop firmata device", ex);
        }
        try {
            parserExecutor.join();
        } catch (InterruptedException ex) {
            LOGGER.warn("Cannot stop parser thread", ex);
        }
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        // queueing data from input buffer to processing by FSM logic
        if (event.isRXCHAR() && event.getEventValue() > 0) {
            try {
                //noinspection StatementWithEmptyBody
                while (!byteQueue.offer(port.readBytes())) {
                    // trying to place bytes to queue until it succeeds
                }
            } catch (SerialPortException ex) {
                LOGGER.error("Cannot read from device", ex);
            }
        }
    }

    /**
     * Sends the message to connected Firmata device using open port.<br/>
     * This method is package-wide accessible to be used by {@link FirmataPin}.
     *
     * @param msg the Firmata message
     * @throws IOException when writing fails
     */
    void sendMessage(byte[] msg) throws IOException {
        try {
            port.writeBytes(msg);
        } catch (SerialPortException ex) {
            throw new IOException("Cannot send message to device", ex);
        }
    }

}

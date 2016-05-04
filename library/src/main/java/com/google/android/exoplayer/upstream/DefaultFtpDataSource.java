/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.upstream;

import android.util.Log;

import com.google.android.exoplayer.C;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

/**
 * A Ftp {@link DataSource}.
 */
public final class DefaultFtpDataSource implements UriDataSource {

  /**
   * Thrown when an error is encountered when trying to read from a {@link DefaultFtpDataSource}.
   */
  public static final class FtpDataSourceException extends IOException {

    public FtpDataSourceException(String message) {
      super(message);
    }

    public FtpDataSourceException(IOException cause) {
      super(cause);
    }

  }

  /**
   * The default maximum datagram packet size, in bytes.
   */
  public static final int DEFAULT_MAX_PACKET_SIZE = 8 *1024 * 1024;

  /**
   * The default socket timeout, in milliseconds.
   */
  public static final int DEAFULT_SOCKET_TIMEOUT_MILLIS = 8 * 1000;

  private final TransferListener listener;

  private DataSpec dataSpec;
  private boolean opened = false;
  private long bytesToSkip = 0, bytesSkipped = 0;
  private long bytesToRead=0, bytesRead=0;
  
  private  FTPClient ftpClient; 
  private InputStream stream;
  
  private static final AtomicReference<byte[]> skipBufferReference = new AtomicReference<>();
  /**
   * @param listener An optional listener.
   */
  public DefaultFtpDataSource(TransferListener listener) {
	 this(DEAFULT_SOCKET_TIMEOUT_MILLIS, DEFAULT_MAX_PACKET_SIZE, listener);
  }
  
  public DefaultFtpDataSource(int timeout, int bufferSize, TransferListener listener) {
	  this.listener = listener;
	  ftpClient = new FTPClient();
	  ftpClient.setDataTimeout(timeout);
	  ftpClient.setReceieveDataSocketBufferSize(bufferSize);	  	  
	  Log.i("ftp", "new FtpDataSource");
  }

  private long getFileSize(FTPClient ftp, String filePath) throws IOException {
	    long fileSize = 0;
	    FTPFile[] files = ftp.listFiles(filePath);
	    if (files.length == 1 && files[0].isFile()) {
	        fileSize = files[0].getSize();
	    }
	    return fileSize;
	}
  
  boolean isLogin = false;
  
  @Override
  public long open(DataSpec dataSpec) throws FtpDataSourceException {
    this.dataSpec = dataSpec;
    this.bytesRead = 0;
    this.bytesSkipped = 0;

    String host = dataSpec.uri.getHost();
    int port = dataSpec.uri.getPort();
    if(port<=0) port=FTP.DEFAULT_PORT;
    String filePath = dataSpec.uri.getPath();
   String userInfo = dataSpec.uri.getUserInfo();
   bytesToRead = C.LENGTH_UNBOUNDED;
    try {
//    	if(!isLogin){
	    	ftpClient.connect(host, port);
	    	ftpClient.enterLocalPassiveMode();
	    	ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
	    	StringTokenizer tok = new StringTokenizer(userInfo, ":@");
	    	String user="anonymous",pass="";
	    	if(tok.countTokens() > 0){
	    		user = tok.nextToken();
	    		if(tok.hasMoreTokens())
	    			pass = tok.nextToken();
	    	}
	    	
	    	isLogin = ftpClient.login(user, pass);    
	    	Log.i("ftp", "fpt login:" + user + ":" + pass + "@" + host + ":" + port + " - " + isLogin + " pos:" + dataSpec.position);
	    	if(!isLogin)
	    		 throw new FtpDataSourceException("login failed.");
	    	ftpClient.setFileTransferMode(FTP.COMPRESSED_TRANSFER_MODE);
//    	}
    	bytesToRead = getFileSize(ftpClient, filePath);    	
    	stream = ftpClient.retrieveFileStream(filePath);
    } catch (IOException e) {
    	 throw new FtpDataSourceException(e);
	}
    
    bytesToSkip = dataSpec.position;
    opened = true;
    if (listener != null) {
      listener.onTransferStart();
    }
    return bytesToRead;
  }

  /**
   * Skips any bytes that need skipping. Else does nothing.
   * <p>
   * This implementation is based roughly on {@code libcore.io.Streams.skipByReading()}.
   *
   * @throws InterruptedIOException If the thread is interrupted during the operation.
   * @throws EOFException If the end of the input stream is reached before the bytes are skipped.
   */
  private void skipInternal() throws IOException {
    if (bytesSkipped == bytesToSkip) {
      return;
    }
    
    // Acquire the shared skip buffer.
    byte[] skipBuffer = skipBufferReference.getAndSet(null);
    if (skipBuffer == null) {
      skipBuffer = new byte[4096];
    }

    while (bytesSkipped != bytesToSkip) {
      int readLength = (int) Math.min(bytesToSkip - bytesSkipped, skipBuffer.length);
      int read = stream.read(skipBuffer, 0, readLength);
      if (Thread.interrupted()) {
        throw new InterruptedIOException();
      }
      if (read == -1) {
        throw new EOFException();
      }
      bytesSkipped += read;
      if (listener != null) {
        listener.onBytesTransferred(read);
      }
    }

    // Release the shared skip buffer.
    skipBufferReference.set(skipBuffer);
  }
  
  /**
   * Reads up to {@code length} bytes of data and stores them into {@code buffer}, starting at
   * index {@code offset}.
   * <p>
   * This method blocks until at least one byte of data can be read, the end of the opened range is
   * detected, or an exception is thrown.
   *
   * @param buffer The buffer into which the read data should be stored.
   * @param offset The start offset into {@code buffer} at which data should be written.
   * @param readLength The maximum number of bytes to read.
   * @return The number of bytes read, or {@link C#RESULT_END_OF_INPUT} if the end of the opened
   *     range is reached.
   * @throws IOException If an error occurs reading from the source.
   */
  private int readInternal(byte[] buffer, int offset, int readLength) throws IOException {
    readLength = bytesToRead == C.LENGTH_UNBOUNDED ? readLength
        : (int) Math.min(readLength, bytesToRead - bytesRead);
    if (readLength == 0) {
      // We've read all of the requested data.
      return C.RESULT_END_OF_INPUT;
    }

    int read = stream.read(buffer, offset, readLength);
    if (read == -1) {
      if (bytesToRead != C.LENGTH_UNBOUNDED && bytesToRead != bytesRead) {
        // The server closed the connection having not sent sufficient data.
        throw new EOFException();
      }
      return C.RESULT_END_OF_INPUT;
    }

    bytesRead += read;
    if (listener != null) {
      listener.onBytesTransferred(read);
    }
    return read;
  }
  @Override
  public int read(byte[] buffer, int offset, int readLength) throws FtpDataSourceException {
      try {
    	  skipInternal();
          return readInternal(buffer, offset, readLength);
      } catch (IOException e) {
        throw new FtpDataSourceException(e);
      }
  }

  @Override
  public void close() {
	  Log.i("ftp", "bye");
	  if (opened) {
		  opened = false;
		  if (listener != null) {
			  listener.onTransferEnd();
		  }

		  try {
			  stream.close();
			  ftpClient.logout();
			  ftpClient.disconnect();
		  } catch (IOException e) {
			  e.printStackTrace();
		  }
    }
  }

  @Override
  public String getUri() {
    return dataSpec == null ? null : dataSpec.uri.toString();
  }

}

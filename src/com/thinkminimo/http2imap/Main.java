package com.thinkminimo.http2imap;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class Main {

  public class Client extends Thread {
    private ByteBuffer buf = null;
    private SocketChannel sock = null;

    private static final int BUF_SIZE = 1024;

    public ConcurrentLinkedQueue<String>    in;
    public ConcurrentLinkedQueue<String>    out;
    public ConcurrentLinkedQueue<Exception> err;
    public AtomicBoolean                    stop;
    public boolean                          isConnected = true;

    public Client(String host, int port) throws Exception {
      in    = new ConcurrentLinkedQueue<String>();
      out   = new ConcurrentLinkedQueue<String>();
      err   = new ConcurrentLinkedQueue<Exception>();
      stop  = new AtomicBoolean();
      buf   = ByteBuffer.allocateDirect(BUF_SIZE);

      sock = SocketChannel.open();
      sock.configureBlocking(true);
      sock.connect(new InetSocketAddress(host, port));
      sock.configureBlocking(false);
    }

    public void run() {

      while (! stop.get()) {
        int nread = -1;
        StringBuilder str = new StringBuilder();

        do {
          try {
            buf.clear();

            if ((nread = sock.read(buf)) <= 0)
              break;

            buf.flip();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            str.append(new String(bytes));
          } catch (Exception e) {
            err.add(e);
          }
        }  while (nread > 0);

        if (nread < 0)
          break;

        if (str.length() > 0)
          in.add(str.toString());

        String s = out.poll();

        if (s != null) {
          try {
            nread = sock.write(ByteBuffer.wrap(s.getBytes()));
          } catch (Exception e) {
            err.add(e);
          }
        }
      }

      this.isConnected = false;
      try {
        sock.close();
      } catch (Exception e) {
      }
    }
  }

  public Main(String host, int port) {
    try {
      Client c = new Client(host, port);
      //c.setDaemon(true);
      c.start();
      while (c.isConnected) {
        String s = c.in.poll();
        if (s != null) {
          System.out.print(s);
          System.out.print("> ");
          c.out.add((new BufferedReader(
                  new InputStreamReader(System.in))).readLine()+"\r\n");
        }
      }
    } catch (Exception e) {
      System.err.println("http2imap: " + e);
    }
  }

  public static void main(String[] args) {
    new Main(args[0], Integer.parseInt(args[1]));
  }
}

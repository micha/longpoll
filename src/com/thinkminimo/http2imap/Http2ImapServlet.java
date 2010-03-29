package com.thinkminimo.http2imap;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.servlet.*;
import javax.servlet.http.*;

public class Http2ImapServlet extends HttpServlet {

  public static final String ATTR_CLIENTS = "clients";
  
  public class Client {

    private String host;
    private int port;
    private Socket sock;
    private Thread reader, writer;

    public final LinkedBlockingQueue<String> q_in
      = new LinkedBlockingQueue<String>();
    public final LinkedBlockingQueue<String> q_out
      = new LinkedBlockingQueue<String>();
    public final AtomicBoolean connected = new AtomicBoolean(false);

    private class ClientReader implements Runnable {
      public void run() {
        try {
          InputStream in = sock.getInputStream();
          byte[] b = new byte[4096];
          int    n = 0;

          while (!sock.isClosed() && (n = in.read(b)) >= 0) {
            String s = new String(b, 0, n);

            while (in.available() > 0)
              if ((n = in.read(b)) >= 0)
                s += new String(b, 0, n);

            q_in.put(s);
          }
        }

        catch (Exception e) {
          System.err.println("Exception: "+e);
        }

        finally {
          disconnect();
        }
      }
    }

    private class ClientWriter implements Runnable {
      public void run() {
        try {
          String line;

          while (connected.get()) {
            try {
              line = q_out.take();
            } catch (InterruptedException ie) {
              continue;
            }
            if (sock.isClosed())
              break;
            else
              sock.getOutputStream().write(line.getBytes());
          }
        }

        catch (Exception e) {
          System.err.println("Exception: "+e);
        }

        finally {
          disconnect();
        }
      }
    }

    public Client(String host, int port) {
      this.host   = host;
      this.port   = port;

      try {
        sock = new Socket(host, port);
        connected.set(true);

        reader = new Thread(new ClientReader());
        reader.setDaemon(true);
        reader.start();

        writer = new Thread(new ClientWriter());
        writer.setDaemon(true);
        writer.start();
      }

      catch (Exception e) {
        System.err.println("Exception: "+e);
      }
    }

    public void disconnect() {
      connected.set(false);
      try {
        sock.close();
      } catch (Exception e) { }
      writer.interrupt();
    }
  }


  public void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {

    HttpSession session = request.getSession(true);
    String sid  = session.getId();
    String host = request.getParameter("rhost");
    String port = request.getParameter("rport");
    String ret="", s=null, cmd=null;

    try {
      ConcurrentHashMap<String,Client> clients = getClients();
      Client c = clients.get(sid);

      if (c == null || !c.connected.get() || (host != null && port != null)) {
        host = host != null ? host : (String) session.getAttribute("rhost");
        port = port != null ? port : (String) session.getAttribute("rport");
        
        if (host == null || port == null) {
          throw new Exception("client disconnected");
        } else {
          session.setAttribute("rhost", host);
          session.setAttribute("rport", port);
        }

        for (String key : clients.keySet())
          if (clients.get(key) == null || !clients.get(key).connected.get())
            clients.remove(key);
            
        c = new Client(host, Integer.parseInt(port));
        clients.put(sid, c);
      }

      try {
        ret = c.q_in.take();
      } catch (InterruptedException ie) { }

      response.setStatus(200);
      response.setContentType("text/plain");
      response.getWriter().print(ret);
    }

    catch (Exception x) {
      // 500 INTERNAL SERVER ERROR
      x.printStackTrace();
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.setContentType("text/plain");

      PrintWriter out = response.getWriter();
      out.print("servlet error :(\n");
      out.print(x.getMessage());
    }
  }

  public void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {

    String sid = request.getSession(true).getId();
    String ret="", s=null, cmd=null;

    try {
      Client c = getClients().get(sid);

      if (c == null || !c.connected.get()) {
        getClients().remove(sid);
        throw new Exception("client disconnected");
      }

      if ((cmd = request.getParameter("command")) != null)
        c.q_out.put(cmd);

      response.setStatus(200);
    }

    catch (Exception x) {
      // 500 INTERNAL SERVER ERROR
      x.printStackTrace();
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.setContentType("text/plain");

      PrintWriter out = response.getWriter();
      out.print("servlet error :(\n");
      out.print(x.getMessage());
    }
  }

  /**
   * Convenience function to do html entity encoding.
   *
   * @param     s         the string to encode
   * @return              the encoded string
   */
  public static String HTMLEntityEncode(String s) {
    StringBuffer buf = new StringBuffer();
    int len = (s == null ? -1 : s.length());

    for ( int i = 0; i < len; i++ ) {
      char c = s.charAt( i );
      if ( c>='a' && c<='z' || c>='A' && c<='Z' || c>='0' && c<='9' ) {
        buf.append( c );
      } else {
        buf.append("&#" + (int)c + ";");
      }
    }

    return buf.toString();
  }

  private ConcurrentHashMap<String,Client> getClients() {
    ConcurrentHashMap<String,Client> clients =
      (ConcurrentHashMap<String,Client>) 
        getServletContext().getAttribute(ATTR_CLIENTS);

    if (clients == null) {
      clients = new ConcurrentHashMap<String,Client>();
      getServletContext().setAttribute(ATTR_CLIENTS, clients);
    }

    return clients;
  }
}

package org.tron.core.services.http;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.config.args.Args;
import org.tron.core.services.http.BroadcastServlet;
import org.tron.core.services.http.GetAccountServlet;
import org.tron.common.application.Service;
import org.tron.core.services.http.TransactionSignServlet;
import org.tron.core.services.http.TransferServlet;

@Component
//@Slf4j
public class FullNodeHttpApiService implements Service{

  private int port = Args.getInstance().getHttpPort();

  private Server server;

  @Autowired
  private GetAccountServlet accountServlet;
  @Autowired
  private TransferServlet transferServlet;
  @Autowired
  private BroadcastServlet broadcastServlet;
  @Autowired
  private TransactionSignServlet transactionSignServlet;

  @Override
  public void init() {

  }

  @Override
  public void init(Args args) {

  }

  @Override
  public void start() {
    try {
      server = new Server(port);
      ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
      context.setContextPath("/");
      server.setHandler(context);
      context.addServlet(new ServletHolder(accountServlet), "/getaccount");
      context.addServlet(new ServletHolder(transferServlet), "/createtransaction");
      context.addServlet(new ServletHolder(broadcastServlet), "/broadcasttransaction");
      context.addServlet(new ServletHolder(transactionSignServlet), "/gettransactionsign");
      server.start();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void stop() {
    try {
      server.stop();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

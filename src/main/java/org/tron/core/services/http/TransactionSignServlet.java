package org.tron.core.services.http;

import java.io.IOException;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.TransactionSign;


@Component
@Slf4j
public class TransactionSignServlet extends HttpServlet {
  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String contract = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
    TransactionSign.Builder build = TransactionSign.newBuilder();
    JsonFormat.merge(contract, build);
    TransactionCapsule retur = wallet.getTransactionSign(build.build());
    response.getWriter().println(JsonFormat.printToString(retur.getInstance()));
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    doGet(request, response);
  }
}

package org.eclipse.jetty.http3.server;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

public class End2EndServerTest
{
    @Test
    void name() throws Exception
    {
        Server server = new Server();

        SSLKeyPair keyPair = new SSLKeyPair(new File("src/test/resources/keystore.p12"), "PKCS12", "storepwd".toCharArray(), "mykey", "storepwd".toCharArray());
        QuicConnector quicConnector = new QuicConnector(server, "http/0.9");
        quicConnector.setPort(8443);
        quicConnector.setKeyPair(keyPair);
        HttpConfiguration config = new HttpConfiguration();
        config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
        HttpConnectionFactory factory = new HttpConnectionFactory(config);
        quicConnector.addConnectionFactory(factory);
        server.addConnector(quicConnector);

        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                PrintWriter writer = response.getWriter();
                writer.println("<html>\n" +
                    "\t<body>\n" +
                    "\t\tRequest served\n" +
                    "\t</body>\n" +
                    "</html>");
            }
        });

        server.start();

        System.out.println("Started.");
        System.in.read();

        server.stop();
    }
}
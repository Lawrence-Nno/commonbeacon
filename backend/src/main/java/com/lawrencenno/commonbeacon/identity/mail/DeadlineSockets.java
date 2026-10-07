package com.lawrencenno.commonbeacon.identity.mail;

import java.io.IOException;
import java.net.*;
import javax.net.SocketFactory;
import javax.net.ssl.*;

/** Uses the platform trust store; the enclosing deadline can close connect/TLS/write/read. */
final class DeadlineSockets {
    private DeadlineSockets() {}
    static SocketFactory plain(MailAttempt attempt, int timeout) {
        return new SocketFactory() {
            @Override public Socket createSocket() { return attempt.watch(new Socket()); }
            private Socket connect(String host, int port, InetAddress local, int localPort) throws IOException {
                Socket socket = createSocket(); if (local != null) socket.bind(new InetSocketAddress(local, localPort));
                socket.connect(new InetSocketAddress(host, port), timeout); return socket;
            }
            @Override public Socket createSocket(String host,int port) throws IOException { return connect(host,port,null,0); }
            @Override public Socket createSocket(InetAddress host,int port) throws IOException { return connect(host.getHostAddress(),port,null,0); }
            @Override public Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException { return connect(host,port,local,localPort); }
            @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort) throws IOException { return connect(host.getHostAddress(),port,local,localPort); }
        };
    }
    static SSLSocketFactory tls(MailAttempt attempt, int timeout) {
        var delegate = (SSLSocketFactory) SSLSocketFactory.getDefault(); var plain = plain(attempt, timeout);
        return new SSLSocketFactory() {
            @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
            @Override public Socket createSocket() throws IOException { return attempt.watch(delegate.createSocket()); }
            @Override public Socket createSocket(Socket raw,String host,int port,boolean close) throws IOException { attempt.watch(raw); return attempt.watch(delegate.createSocket(raw,host,port,close)); }
            @Override public Socket createSocket(String host,int port) throws IOException { return createSocket(plain.createSocket(host,port),host,port,true); }
            @Override public Socket createSocket(InetAddress host,int port) throws IOException { return createSocket(host.getHostAddress(),port); }
            @Override public Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException { return createSocket(plain.createSocket(host,port,local,localPort),host,port,true); }
            @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort) throws IOException { return createSocket(host.getHostAddress(),port,local,localPort); }
        };
    }
}

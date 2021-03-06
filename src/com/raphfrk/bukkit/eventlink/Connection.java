/*******************************************************************************
 * Copyright (C) 2012 Raphfrk
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
package com.raphfrk.bukkit.eventlink;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

public class Connection  {

	private final EventLink p;

	private final Connection thisConnection;

	private final Socket s;

	private final ConnectionManager connectionManager;

	private final String serverName;

	private final ObjectInputStream in;
	private final ObjectOutputStream out;

	private final InConnection inConnection;
	private final OutConnection outConnection;

	private final KillableThread inThread;
	private final KillableThread outThread;

	private final Object syncObject;

	Connection(ConnectionManager connectionManager, Object syncObject, EventLink p, Socket s, ObjectInputStream in, ObjectOutputStream out, String serverName) {
		this.connectionManager = connectionManager;
		this.p = p;
		this.s = s;
		this.serverName = serverName;
		this.syncObject = syncObject;

		try {
			s.setSoTimeout(1000);
		} catch (SocketException e) {
			p.log("Failed to set socket timeout");
		}

		this.out = out;
		this.in = in;

		if(in != null && out != null) {

			outConnection = new OutConnection();
			outThread = outConnection;
			outThread.setName("Out connection: " + s.getInetAddress().getHostAddress() + ":" + s.getPort());
			outThread.start();

			inConnection = new InConnection();
			inThread = inConnection;
			inThread.setName("In connection: " + s.getInetAddress().getHostAddress() + ":" + s.getPort());
			inThread.start();

			thisConnection = this;
		} else {
			outConnection = null;
			outThread = null;

			inConnection = null;
			inThread = null;

			thisConnection = null;
		}

	}

	public boolean joinConnection() throws InterruptedException {
		if(inThread != null) {
			inThread.join(100);
		}
		if(outThread != null) {
			outThread.join(100);
		}
		return !whichAlive().equals("");
	}

	String whichAlive() {
		String temp = "";
		if(inThread != null && inThread.isAlive()) {
			temp = "In ";
		}
		if(outThread != null && outThread.isAlive()) {
			temp = temp + "out";
		}
		return temp;
	}

	void interruptConnection() {
		if(inConnection != null) {
			inConnection.interrupt();
		}
		if(outConnection != null) {
			outConnection.interrupt();
		}
	}

	boolean getAlive() {
		return (inConnection != null && inConnection.isAlive()) || 
		(outConnection != null && outConnection.isAlive());
	}

	String getServerName() {
		return serverName;
	}

	public void send(EventLinkPacket eventLinkPacket) {
		outConnection.send(eventLinkPacket);
	}

	final AtomicBoolean closeLock = new AtomicBoolean(false);

	private class OutConnection extends KillableThread {

		private LinkedList<EventLinkPacket> sendQueue = new LinkedList<EventLinkPacket>();

		public void send(EventLinkPacket eventLinkPacket) {

			synchronized(sendQueue) {
				sendQueue.addLast(eventLinkPacket);
				sendQueue.notify();
			}

		}

		public void run() {

			while(!killed()) {

				EventLinkPacket next = null;

				while(next == null && !killed()) {
					synchronized(sendQueue) {
						if(!killed() && sendQueue.isEmpty()) {
							try {
								sendQueue.wait(200);
							} catch (InterruptedException e) {
								kill();
								continue;
							}
						} else {
							next = sendQueue.removeFirst();
						}
					}
				}
				if(killed()) {
					continue;
				}
				try {
					synchronized(out) {
						out.reset();
						synchronized(next.payload) {
							out.writeObject(next); 
						}
					}
				} catch (OptionalDataException ode) {
					if(!ode.eof) {
						p.log("Optional Data Exception with connection from: " + serverName);
						ode.printStackTrace();
					}
					kill();
					continue;
				} catch (IOException e) {
					p.log("Object write error to " + serverName);
					kill();
					continue;
				}
			}
			synchronized(connectionManager.activeConnections) {
				if(!inConnection.isAlive()) {
					boolean removed = connectionManager.activeConnections.remove(serverName, thisConnection);

					if(removed) {
						p.log("Closing connection to " + serverName);
						p.log("About to close routes");
						p.routingTableManager.clearRoutesThrough(serverName);
						p.log("Routes cleared");
					} else {
						p.log("Closing expired connection to " + serverName);
					}
				}
			}


			boolean canSkipClose = closeLock.compareAndSet(false, true);
			
			if(!canSkipClose) {
				SSLUtils.closeSocket(s);
			} 

			synchronized(syncObject) {
				syncObject.notify();
			}
		}
	}

	public EventLinkPacket receive() {
		return inConnection.receive();
	}

	public boolean isEmpty() {
		return inConnection.isEmpty();
	}


	private class InConnection extends KillableThread {

		private LinkedList<EventLinkPacket> receiveQueue = new LinkedList<EventLinkPacket>();

		public EventLinkPacket receive() {

			synchronized(receiveQueue) {
				if(receiveQueue.isEmpty()) {
					return null;
				} else {
					return receiveQueue.removeFirst();
				}
			}

		}

		public boolean isEmpty() {

			synchronized(receiveQueue) {
				return receiveQueue.isEmpty();
			}

		}

		public void run() {

			while(!killed()) {

				Object obj = null;
				try {
					obj = in.readObject();
				} catch (SocketTimeoutException ste) {
					continue;
				} catch (SocketException se) {
					kill();
					continue;
				} catch (EOFException eof) {
					kill();
					continue;
				} catch (OptionalDataException ode) {
					if(!ode.eof) {
						p.log("Optional Data Exception with connection from: " + serverName);
						ode.printStackTrace();
					}
					kill();
					continue;
				} catch (IOException e) {
					p.log("IO Error with connection from: " + serverName);
					e.printStackTrace();
					kill();
					continue;
				} catch (ClassNotFoundException e) {
					p.log("Received unknown class from: " + serverName);
					kill();
					continue;
				}

				if(!(obj instanceof EventLinkPacket)) {
					p.log("Non-packet received (" + obj.getClass() + "): " + serverName);
					kill();
					continue;
				}

				EventLinkPacket eventLinkPacket = (EventLinkPacket)obj;

				synchronized(syncObject) {
					synchronized(receiveQueue) {
						if(eventLinkPacket!=null) {
							receiveQueue.addLast(eventLinkPacket);
							syncObject.notify();
						}
					}
				}
			}

			boolean clearRoutes = false;
			
			synchronized(connectionManager.activeConnections) {
				boolean removed = connectionManager.activeConnections.remove(serverName, thisConnection);

				if(removed) {
					p.log("Closing connection to " + serverName);
					p.log("About to close routes");
					clearRoutes = true;
				} else {
					p.log("Closing expired connection to " + serverName);
				}
			}
			
			if (clearRoutes) {
				p.routingTableManager.clearRoutesThrough(serverName);
				p.log("Routes cleared");
			}

			boolean canSkipClose = closeLock.compareAndSet(false, true);
			
			if(!canSkipClose) {
				SSLUtils.closeSocket(s);
			}

			synchronized(syncObject) {
				syncObject.notify();
			}
		}
	}
}

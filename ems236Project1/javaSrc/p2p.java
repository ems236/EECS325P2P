import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class p2p 
{
	//maintain list of currently connected peers, the sockets they're associated with, and the timeout.
	public static ArrayList<PeerId> peerConns = new ArrayList<PeerId>();
	
	//will be read from config file
	//stores local IP and port for data connections
	public static PeerId myId;
	
	//list of neighbors for when Connect is called;
	public static ArrayList<PeerId> neighbors = new ArrayList<PeerId>();
	
	//list of welcome sockets (Should only be 2)
	public static ArrayList<ServerSocket> welcomes = new ArrayList<ServerSocket>();
	
	//outstanding queries
	public static ArrayList<Query> queries = new ArrayList<Query>(); 
	
	//list of files available
	public static ArrayList<String> fileList = new ArrayList<String>();
	 
	//for synchronization across threads 
	public static final Object syncObjP = new Object();
	public static final Object syncObjQ = new Object();
	
	
	public static int queryPort;
	public static int dataPort;
	//Constants
	//time out on sockets
	public static final long TO = 300000;
	//how often to check timers
	public static final int checkTimers = 60;
	//buffer size for file transfer
	public static final int bSize = 100000;
	
	public static void main(String[] args)
	{
		System.out.println("Starting");
		//define ports (Only need 2?)
		//Find in config file
		Path path = Paths.get("config_neighbors.txt");
		try
		{
			BufferedReader configRead = Files.newBufferedReader(path, Charset.defaultCharset());
			String line = configRead.readLine();
			line = configRead.readLine();
		
			boolean doneOne = false;
			while(line != null)
			{
				String[] fields = new String[3];
				fields[0] = "";
				int counter = 0;
				for(int i = 0; i < line.length(); i++)
				{
					if(line.charAt(i) == ',')
					{
						counter++;
						//can't appending to null. it didn't work right
						fields[counter] = "";
					}
					else
					{
						fields[counter] += line.charAt(i);
					}
				}
				
				if(!doneOne)
				{
					queryPort = Integer.parseInt(fields[1]);
					dataPort = Integer.parseInt(fields[2]);
					
					//Ignore socket and ttl for this
					myId = new PeerId(InetAddress.getByName(fields[0]), dataPort, null, 0);
				}
				else
				{
					neighbors.add(new PeerId(InetAddress.getByName(fields[0]), Integer.parseInt(fields[1]), null, 0));
				}
				doneOne = true;
				line = configRead.readLine();
			}
		
			configRead.close();
		
			path = Paths.get("config_sharing.txt");
			configRead = Files.newBufferedReader(path, Charset.defaultCharset());
			
			line = configRead.readLine();
			while(line != null)
			{
				fileList.add(line);
				line = configRead.readLine();
			}
			configRead.close();
		}
		catch(IOException e)
		{
			System.out.println("Error reading config files");
			System.exit(1);
		}
		
		try
		{
			//set up welcome sockets
			//Welcome socket for Queries
			new Thread(new WelcomeSocketQuery(queryPort)).start();
			
			//Welcome Socket for File transfer
			new Thread(new WelcomeSocketData(dataPort)).start();
		}
		catch(IOException e)
		{
			System.out.println("Error Opening welcome sockets");
			System.out.println("run lsof -i :" + queryPort + "and kill that process.");
		}
		
		//Time handling thread
		new Thread
		(new TimeOutHandler()).start();
		
		//set up listening to user
		Scanner scan = new Scanner(System.in);
		String input;
		while(true)
		{
			System.out.println("Enter a Command (Case sensitive. Filenames NOT enclosed in <>)");
			input = scan.nextLine();
			if(input.equals("Connect"))
			{
				//testing only
				//InetAddress addr = InetAddress.getByName("129.22.164.60");
				//new Thread(new QuerySocket(new Socket(addr, 50640))).start();
				
				
				for(int i = 0; i < neighbors.size(); i++)
				{
					boolean shouldConn = true;
					synchronized(syncObjP)
					{
						//check if already connected
						for(int j = 0; j < peerConns.size(); j++)
						{
							if(neighbors.get(i).equals(peerConns.get(j)))
							{
								shouldConn = false;
							}
						}
					}
					
					if(shouldConn)
					{
						System.out.println("Connecting to " + neighbors.get(i).getIp());
						try
						{
							new Thread(new QuerySocket(new Socket(neighbors.get(i).getIp(), neighbors.get(i).getPort()))).start();
							//Should go to catch block if it fails
							System.out.println("Successfully connected to " + neighbors.get(i).getIp());
						}
						catch(IOException e)
						{
							System.out.println("Failed connecting to " + neighbors.get(i).getIp());
						}
					}
					
				}
			}
			
			else if(input.substring(0, 3).equals("Get"))
			{
				String fileName = input.substring(4);
				//build query
				//generate QueryId and query object
				Query q = new Query(UUID.randomUUID().toString(), null, 'Q', fileName);
				synchronized(syncObjQ)
				{
					queries.add(q);
				}
				sendMsg(q);
			}
			
			else if(input.equals("Leave"))
			{
				try 
				{
					synchronized(syncObjP)
					{
						for(int i = peerConns.size() - 1; i >= 0; i--)
						{
							peerConns.get(i).getSocket().close();
							peerConns.remove(i);
						}
					}
				}
				
				catch(IOException e)
				{
					System.out.println("Error closing sockets");
					System.exit(1);
				}
			}
			
			else if(input.equals("Exit"))
			{
				try 
				{
					synchronized(syncObjP)
					{
						for(int i = peerConns.size() - 1; i >= 0; i--)
						{
							peerConns.get(i).getSocket().close();
							peerConns.remove(i);
						}
					}
					
					for(int i = welcomes.size() - 1; i >= 0; i--)
					{
						welcomes.get(i).close();
						welcomes.remove(i);
					}
				}
				
				catch(IOException e)
				{
					System.out.println("Error closing sockets");
					System.exit(1);
				}
				
				//close Data sockets?
				scan.close();
				System.exit(0);
			}
			
			else
			{
				System.out.println("Invalid command.");
			}
		}
	}
	
	//make nested classes for multithreading
	static class WelcomeSocketQuery implements Runnable
	{
		ServerSocket socket;
		public WelcomeSocketQuery(int port) throws IOException
		{
			socket = new ServerSocket(port);
			welcomes.add(socket);
		}
		
		public void run()
		{
			while(!socket.isClosed())
			{
				try
				{
					Thread thread = new Thread(new QuerySocket(socket.accept()));
					thread.start();
					System.out.println("Accepting connection from peer");
				}
				catch(SocketException e)
				{
					//do nothing this gets thrown every time you exit
				}
				catch(IOException e)
				{
					System.out.println("error with welcome socket");
				}
			}
		}
	}
	
	static class WelcomeSocketData implements Runnable
	{
		ServerSocket socket;
		public WelcomeSocketData(int port) throws IOException
		{
			socket = new ServerSocket(port);
			welcomes.add(socket);
		}
		
		public void run()
		{
			while(!socket.isClosed())
			{
				try
				{
					Thread thread = new Thread(new DataSocket(socket.accept(), null, true));
					thread.start();
				}
				catch(SocketException e)
				{
					//do nothing, this gets thrown every time you exit
				}
				catch(IOException e)
				{
					System.out.println("error with welcome socket");
				}
			}
		}
	}

	//thread to handle timeouts
	static class TimeOutHandler implements Runnable
	{
		//check all timers and send heartbeat messages every minute, close socket if necessary if timeout.
		//Socket will throw IOException if it's closed while blocking for read
		public void run()
		{
			while(true)
			{
				try
				{
					TimeUnit.SECONDS.sleep(checkTimers);
				}
				catch(Exception e)
				{
					System.out.println("Can't sleep need help");
				}
				
				synchronized(syncObjP)
				{
					for(int i = 0; i < peerConns.size(); i++)
					{
						if(peerConns.get(i).getTtl() < System.currentTimeMillis())
						{
							//timeout event
							try
							{
								System.out.println("Connection to " + peerConns.get(i) + " has timed out");
								peerConns.get(i).getSocket().close();
								peerConns.remove(i);
								i--;
							}
							catch(IOException e)
							{
								System.out.println("Error closing socket");
							}
						}
						
						else
						{
							Query q = new Query("", peerConns.get(i), 'H', "");
							sendMsg(q);
						}
					}
				}
			}
		}
	}
	
	//creates a socket and listens on it. Sending queries is a different thread.
	static class QuerySocket implements Runnable
	{
		private Socket socket;
		private BufferedReader input;
		//5 min
		private PeerId id;
		
		public QuerySocket(Socket socket)
		{
			synchronized(syncObjP)
			{
				this.socket = socket;
				InetAddress address = socket.getInetAddress();
				int port = socket.getPort();
				id = new PeerId(address, port, socket, System.currentTimeMillis() + TO);
				try
				{
					input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				}
				catch(IOException e)
				{
					System.out.println("Error with datastreams");
					System.exit(1);
				}
				
				peerConns.add(id);
			}
		}
		
		public void run()
		{
			boolean run = true;
			while(run)
			{
				try
				{
					//listens for queries, updates timeout info for timer thread.
					//inputstream blocks until there is data.
					String data = input.readLine();
					if(data == null)
					{
						System.out.println("Socket has been closed: " + id);
						//remove from peerConns
						for(int i = 0; i < peerConns.size(); i++)
						{
							if(peerConns.get(i).equals(id))
							{
								peerConns.remove(i);
								i = peerConns.size();
							}
						}
						run = false;
					}
					
					else
					{			
						//uptdate ttl on any message.
						id.setTtl(System.currentTimeMillis() + TO);
						
						if(data.charAt(0) == 'H')
						{
							System.out.println("Received hearbeat from " + id);
						}
						
						else if(data.charAt(0) == 'Q')
						{
							//handle query
				
							//Always give it to sendMessage().  It gets thrown out there if it needs to.
							String qId = "";
							int i;
							for(i = 3; data.charAt(i) != ')'; i++)
							{
								qId += data.charAt(i);
							}
							
							String message = "";
							i += 3;
							
							for(;data.charAt(i) != ')'; i++)
							{
								message += data.charAt(i);
							}
							
							boolean unique = true;
							Query q = new Query(qId, id, 'Q', message);
							System.out.println("Received Query: " + q);
							
							synchronized(syncObjQ)
							{
								//see if query has already been received
								for(int j = 0; j < queries.size(); j++)
								{
									if(q.equals(queries.get(j)))
									{
										unique = false;
										System.out.println("Duplicate Query; Ignored.");
									}
								}
							}
							
							if(unique)
							{
								synchronized(syncObjQ)
								{
									//Add to query list if it doesn't exist
									queries.add(q);
								}
								
								boolean haveFile = false;
								for(int j = 0; j < fileList.size(); j++)
								{
									if(fileList.get(j).equals(message))
									{
										haveFile = true;
									}
								}
								
								if(haveFile)
								{
									System.out.println("This peer has the requested file.");
									//make reply
									String fileName = message;
									String addr = myId.toString();
									
									Query r = new Query(qId, id, 'R', "(" + addr + ");(" + fileName + ")");
									sendMsg(r);
								}
								
								else
								{
									System.out.println("Peer does not have requested file. Forwarding to other peers.");
									sendMsg(q);
								}
							}	
						}
						
						else if(data.charAt(0) == 'R')
						{
							//handle response
							String qId = "";
							int i;
							for(i = 3; data.charAt(i) != ')'; i++)
							{
								qId += data.charAt(i);
							}
							
							i += 2;
							String message = data.substring(i);
							
							System.out.println("Received a response");
							synchronized(syncObjQ)
							{
								//check outstanding queries
								for(int j = 0; j < queries.size(); j++)
								{	
									//remove this query if the reply gets forwarded / file transfer initiated
									if(queries.get(j).equals(qId))
									{
										Query q = queries.get(j);
										if(q.getSource() == null)
										{
											System.out.println("Received a response to my query.");
											//initiate file transfer
											//get peer
											boolean onPort = false;
											String ip = "";
											String portString = "";
											for(int k = 1; message.charAt(k) != ')'; k++)
											{
												if(message.charAt(k) == ':')
												{
													onPort = true;
												}
												else if(onPort)
												{
													portString += message.charAt(k);
												}
												else
												{
													ip += message.charAt(k);
												}
											}
											
											InetAddress addr = InetAddress.getByName(ip);
											int port = Integer.parseInt(portString);
											
											String fileName = q.getMessage();
											
											new Thread(new DataSocket(new Socket(addr, port), fileName, false)).start();
											//go back to listening	
										}
										
										else
										{
											Query r = new Query(qId, q.getSource(), 'R', message);
											//Other method handles finding the right socket but it would probably make more sense here
											System.out.println("Forwarding respone.");
											sendMsg(r);
										}
				
										queries.remove(j);
										j = queries.size();
									}
								}
							}
						}
					}
				}
				
				catch(IOException e)
				{
					//will exit the thread
					run = false;
					
					//Expect this when socket times out and is closed by another thread.
					//close socket if it's still open
					if(!socket.isClosed())
					{
						try
						{
							socket.close();
						}
						catch(IOException e2)
						{
							System.out.println("Error closing socket");
						}
					}
					
					synchronized(syncObjP)
					{
						//remove connection from peerConns
						for(int i = peerConns.size() - 1; i >= 0; i--)
						{
							if(id.equals(peerConns.get(i)))
							{
								peerConns.remove(i);
							}
						}
					}
				}
			}
		}
	}
	
	//Could make this another thread but I don't think it's worth it.  Should run pretty fast and doesn't have to listen at all.
	public static boolean sendMsg(Query q)
	{
		//Add a delimiter for readline to work  
		String msg = q.toString() + "\n";
		if(q.getType() == 'H')
		{
			//Heartbeat
			try
			{
				DataOutputStream output = new DataOutputStream(q.getSource().getSocket().getOutputStream());
				//send msg
				System.out.println("Sending heartbeat to " + q.getSource());
				output.writeBytes(msg);
			}
			catch(IOException e)
			{
				System.out.println("Error writing to socket");
				System.exit(1);
			}
			
			return true;
		}
		
		else if(q.getType() == 'Q')
		{
			//Query
			//send to all neighbors other than the one that sent it
			synchronized(syncObjP)
			{
				for(int i = 0; i < peerConns.size(); i++)
				{
					if(q.getSource() == null || !q.getSource().equals(peerConns.get(i)))
					{
						//send the query
						try
						{
							DataOutputStream output = new DataOutputStream(peerConns.get(i).getSocket().getOutputStream());
							//write msg to output
							System.out.println("Sending query to " + peerConns.get(i));
							output.writeBytes(msg);
						}
						catch(IOException e)
						{
							System.out.println("Error writing to socket");
							System.exit(1);
						}
					}
				}
				return true;
			}
		}
		
		else
		{
			//Response
			//Query has already been removed from queries.
			try
			{
				DataOutputStream output = new DataOutputStream(q.getSource().getSocket().getOutputStream());
				//write msg
				System.out.println("Sending response to " + q.getSource());
				output.writeBytes(msg);
			}
			catch(IOException e)
			{
				System.out.println("Error writing to socket");
				System.exit(1);
			}
			return true;

		}
	}
	
	static class DataSocket implements Runnable
	{
		private Socket socket;
		private String fileName;
		private boolean isServer;
		public DataSocket(Socket socket, String name, boolean serv)
		{
			this.socket = socket;
			fileName = name;
			isServer = serv;
		}
		
		public void run()
		{
			if(isServer)
			{
				try
				{
					BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
					String msg = input.readLine();
					System.out.println("Received Transfer request");
					String fName = "";
					for(int i = 3;  msg.charAt(i) != ')'; i++)
					{
						fName += msg.charAt(i);
					}
					
					Path path = Paths.get("shared/" + fName);
					
					InputStream fInput = Files.newInputStream(path);
					OutputStream output = socket.getOutputStream();
	
					byte[] buffer = new byte[bSize];
					boolean reading = true;
					
					while(reading)
					{
						int readSize = fInput.read(buffer);
						if(readSize == - 1)
						{
							//should send all data to buffer, iterate again, then do this.
							reading = false;
						}
						else
						{
							output.write(buffer, 0, readSize);
						}
					}
					
					socket.close();
					fInput.close();
					System.out.println("Finished transfering file to peer");
				}
				
				catch(IOException e)
				{
					System.out.println("IO error on file transfer");
					System.exit(1);
				}
			}
			
			else
			{
				//ask for a file
				String message = "T:(" + fileName + ")\n";
				Path path = Paths.get("obtained/" + fileName);
				
				try
				{
					DataOutputStream output = new DataOutputStream(socket.getOutputStream());
					System.out.println("Requesting file from peer");
					output.writeBytes(message);
					 
					InputStream input = socket.getInputStream();
					OutputStream fileOut = Files.newOutputStream(path);
					
					byte[] buffer = new byte[bSize];
					boolean reading = true;
					
					while(reading)
					{
						int readSize = input.read(buffer);
						if(readSize == -1)
						{
							reading = false;
						}
						else
						{
							fileOut.write(buffer, 0, readSize);
						}
					}
					
					//close fileWriter
					socket.close();
					fileOut.close();
					System.out.println("Finished receiving file.");
				}
				catch(IOException e)
				{
					System.out.println("Error with file transfer");
					System.exit(1);
				}
			}
		}
	}
}

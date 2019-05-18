import java.net.*;

public class PeerId 
{
	private Socket socket;
	private InetAddress ip;
	private int port;
	private long ttl;
	
	public PeerId(InetAddress ip, int port, Socket sock, long ttl)
	{
		this.ip = ip;
		this.port = port;
		this.socket = sock;
		this.ttl = ttl;
	}
	
	public boolean equals(PeerId other)
	{
		if(other.getIp().equals(ip))
		{
			return true;
		}
		
		else
		{
			return false;
		}
	}
	
	public Socket getSocket()
	{
		return socket;
	}
	
	public long getTtl()
	{
		return ttl;
	}
	
	public void setTtl(long ttlNew)
	{
		ttl = ttlNew;
	}
	
	public String toString()
	{
		return ip.getHostAddress() + ":" + port;
	}
	
	public InetAddress getIp()
	{
		return ip;
	}
	
	public int getPort()
	{
		return port;
	}
}

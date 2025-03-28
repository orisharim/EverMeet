import socket
import threading

import socket


class Server:
    def __init__(self, server_host, server_port):
        self.server_host = server_host
        self.server_port = server_port

    def start_server(self, receive_command, response_command):
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)  # Use UDP
        server_socket.bind((self.server_host, self.server_port))

        print(f'UDP Server is listening on {self.server_host}:{self.server_port}...')

        while True:
            # Receive data from a client
            message, client_address = server_socket.recvfrom(1024)
            message = message.decode()

            if not message:
                continue  # Ignore empty messages

            print(f'Received message from {client_address}: {message}')
            receive_command(message)

            # Send a response back to the client
            response = response_command(message)
            server_socket.sendto(response.encode(), client_address)

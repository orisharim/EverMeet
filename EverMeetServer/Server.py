import socket
import threading


class Server:

    def __init__(self, server_host, server_port):
        self.server_host = server_host
        self.server_port = server_port

    def handle_client(self, client_socket, client_address, receive_command, response_command):
        print(f'Connection established with {client_address} from {self.server_host}')

        while True:
            # Receive data from the client
            message = client_socket.recv(1024).decode()
            if not message:
                break
            receive_command(message)

            # Send a response back to the client
            response = response_command()
            client_socket.send(response.encode())

        # Close the client socket
        client_socket.close()
        print(f'Connection closed with {client_address}')

    def start_server(self, receive_command, response_command):
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_socket.bind((self.server_host, self.server_port))
        server_socket.listen(5)

        print(f'Server is listening on {self.server_host}:{self.server_port}...')

        while True:
            client_socket, client_address = server_socket.accept()
            client_thread = threading.Thread(target=self.handle_client,
                                             args=(client_socket, client_address, receive_command, response_command))
            client_thread.start()
            print(f'Device connections: {threading.active_count() - 1}')

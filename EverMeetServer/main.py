import tkinter as tk
from Server import Server
import threading


def receive(message):
    print()


def respond(message):
    return message


def on_press():
    print()




def main():
    # setup screen
    screen = tk.Tk()
    screen.title('chiro plate')
    button = tk.Button(screen, text='Send', width=25, command=on_press)
    button.pack()

    # setup server
    server = Server('10.0.0.32', 12345)
    server_thread = threading.Thread(target=server.start_server,
                                     args=(receive, respond))

    # execution
    server_thread.start()
    screen.mainloop()

if __name__=="__main__":
    main()
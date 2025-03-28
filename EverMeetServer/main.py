import tkinter as tk
from Server import Server
import threading
import json
from Database import Database
import Settings 

database = Database(Settings.DB_KEY)
clients = {}  # structure of {plate_id(key): { 'user_id': 0


#                               'user_password': 0
#                               'level_1_duration': 0,
#                               'level_1_difficulty': 0,
#                               .
#                               .
#                               .
#                               'level_x_duration' : 0,
#                               'level_x_difficulty' : 0
#                            }}


def receive(message):
    # message = { plate_id: 0,
    #            user_id: 0,
    #            user_password: 0,    }
    global clients, database
    message = json.loads(message)
    user_info = database.get_user(message['user_id']).get_dict()
    print(user_info)
    # clients[message['plate_id']] = 


def respond(message):
    # return clients[message['plate_id']]
    return ''


def on_press():
    print()




def main():
    global database
    # setup screen
    screen = tk.Tk()
    screen.title('chiro plate')
    button = tk.Button(screen, text='Send', width=25, command=on_press)
    button.pack()

    # setup server
    server = Server('localhost', 12345)
    server_thread = threading.Thread(target=server.start_server,
                                     args=(receive, respond))

    # execution
    server_thread.start()
    screen.mainloop()

import Settings


class User:

    def __init__(self, info: dict):
        self.id = int(info['user_id'])
        self.name = info['user_name']
        self.password = info['user_password']
        self.friend_amount = int(info['friend_amount'])
        self.friends = []
        for i in range(0, self.friend_amount):
            self.friends[i] = int(info[f'friend_id_{i}'])

    def get_dict(self):
        dic = dict()
        dic['user_id'] = self.id
        dic['user_name'] = self.name
        dic['user_password'] = self.password
        for i in range(0, self.friend_amount):
            dic[f'friend_id_{i}'] = self.friends[i]

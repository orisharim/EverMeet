from User import User
import firebase_admin
from firebase_admin import credentials
from firebase_admin import firestore


class Database:

    def __init__(self, db_key: str):
        self.cred = credentials.Certificate(db_key)
        self.app = firebase_admin.initialize_app(self.cred)
        self.client = firestore.client()

    def get_user(self, user_id: int) -> User:
        user_ref = self.client.collection('users').document(str(user_id))
        user_info = user_ref.get().to_dict()
        return User(user_info)

    def add_user(self, user: User):
        user_id = user.id
        user_info = user.get_dict()
        self.client.collection('users').document(str(user_id)).set(user_info)

#!/usr/bin/python
#
# Modified Online Boutique loadgenerator task mix. The upstream task weights
# (index=1, browseProduct=10, ...) interact poorly with Jaeger's in-memory
# trace store under USERS=500 sustained load: the high-volume frontend/cart
# spans evict ListRecommendations spans before the trace pull window, leaving
# the recommendation operation entirely absent from the comparison set.
#
# This variant rebalances the mix so that every Locust user spends most of its
# wait_time looking at pages that call recommendationservice/ListRecommendations
# (the home page `/` and product detail pages `/product/<id>`). Checkout and
# cart-mutation traffic remains present at lower weight so the simulator's
# end-to-end p95 calibration is still exercised.
#
# Weights:
#   index           (calls ListRecommendations on `/`)               : 20
#   browseProduct   (calls ListRecommendations on `/product/<id>`)   : 20
#   addToCart       (calls ListRecommendations + cart mutation)      :  3
#   viewCart                                                          :  2
#   checkout        (calls full PlaceOrder fanout)                   :  1
#   setCurrency                                                       :  1
#
# The mounted location is /loadgen/locustfile.py (subPath) — the upstream
# image's ENTRYPOINT reads this file at container start.

import random
from locust import FastHttpUser, TaskSet, between
from faker import Faker
import datetime
fake = Faker()

products = [
    '0PUK6V6EV0',
    '1YMWWN1N4O',
    '2ZYFJ3GM2N',
    '66VCHSJNUP',
    '6E92ZMYYFZ',
    '9SIQT8TOJO',
    'L9ECAV7KIM',
    'LS4PSXUNUM',
    'OLJCESPC7Z']

def index(l):
    l.client.get("/")

def setCurrency(l):
    currencies = ['EUR', 'USD', 'JPY', 'CAD', 'GBP', 'TRY']
    l.client.post("/setCurrency",
        {'currency_code': random.choice(currencies)})

def browseProduct(l):
    l.client.get("/product/" + random.choice(products))

def viewCart(l):
    l.client.get("/cart")

def addToCart(l):
    product = random.choice(products)
    l.client.get("/product/" + product)
    l.client.post("/cart", {
        'product_id': product,
        'quantity': random.randint(1,10)})

def empty_cart(l):
    l.client.post('/cart/empty')

def checkout(l):
    addToCart(l)
    current_year = datetime.datetime.now().year+1
    l.client.post("/cart/checkout", {
        'email': fake.email(),
        'street_address': fake.street_address(),
        'zip_code': fake.zipcode(),
        'city': fake.city(),
        'state': fake.state_abbr(),
        'country': fake.country(),
        'credit_card_number': fake.credit_card_number(card_type="visa"),
        'credit_card_expiration_month': random.randint(1, 12),
        'credit_card_expiration_year': random.randint(current_year, current_year + 70),
        'credit_card_cvv': f"{random.randint(100, 999)}",
    })

def logout(l):
    l.client.get('/logout')


class UserBehavior(TaskSet):

    def on_start(self):
        index(self)

    tasks = {
        index: 20,
        browseProduct: 20,
        addToCart: 3,
        viewCart: 2,
        checkout: 1,
        setCurrency: 1,
    }


class WebsiteUser(FastHttpUser):
    tasks = [UserBehavior]
    wait_time = between(1, 10)

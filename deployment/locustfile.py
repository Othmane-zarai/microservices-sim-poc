# deployment/locustfile.py
#
# Load model used by EMPIRICAL_VALIDATION.md (§4) to drive the real
# Kubernetes cluster while the simulator runs the matching YAML
# scenario (06-autoscaling-stress.yaml). Four-step browse-and-order
# session; 1000 users; spawn-rate 20; run-time 10 min by default.
#
# Run:
#   locust -f deployment/locustfile.py --host=http://<svc-ip> \
#          --headless --users 1000 --spawn-rate 20 --run-time 10m \
#          --csv deployment/rq2/locust
#
# For the nginx smoke-test workload (deployment/workload.yaml) all
# four endpoints resolve to "GET /" since nginx returns 200 regardless
# of path/method. The endpoint *shape* — distinct names in the stats
# output — is what makes the §7 comparison interesting (per-endpoint
# p50/p95/p99 instead of a single flat line).
from locust import HttpUser, task, between, SequentialTaskSet


class BrowseAndOrder(SequentialTaskSet):
    @task
    def list_products(self):
        self.client.get("/", name="GET /")

    @task
    def view_product(self):
        self.client.get("/?product=42", name="GET /product")

    @task
    def add_to_cart(self):
        self.client.post("/cart", data="productId=42", name="POST /cart")

    @task
    def checkout(self):
        self.client.post("/checkout", data="confirm=1", name="POST /checkout")


class WebUser(HttpUser):
    tasks = [BrowseAndOrder]
    wait_time = between(0.1, 1)

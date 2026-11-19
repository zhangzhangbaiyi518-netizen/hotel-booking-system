FROM docker.elastic.co/elasticsearch/elasticsearch:8.5.3

# IK must match Elasticsearch exactly. Keep it in the image so a container
# recreation preserves the analyzer as well as the separately mounted data.
ARG IK_PLUGIN_URL=https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.5.3.zip
RUN curl --fail --location --retry 3 --connect-timeout 10 --max-time 600 "${IK_PLUGIN_URL}" -o /tmp/analysis-ik.zip \
    && echo "fb7587bc85b498e38e9f3df7ab467133144000ee5de4b5ea25c5d315d0d017c5  /tmp/analysis-ik.zip" | sha256sum -c - \
    && bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip \
    && rm /tmp/analysis-ik.zip

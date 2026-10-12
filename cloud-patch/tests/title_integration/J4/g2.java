package J4;

public final class g2 {
    public final ServiceLink a;
    public Object manager;

    public g2(Object manager, Object service) {
        this.manager = manager;
        a = new ServiceLink(service);
    }
    public Object c() { return manager; }
    public static final class ServiceLink {
        public Object f;
        ServiceLink(Object service) { f = service; }
    }
}
